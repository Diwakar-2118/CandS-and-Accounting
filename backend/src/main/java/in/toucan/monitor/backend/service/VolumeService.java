package in.toucan.monitor.backend.service;

import in.toucan.monitor.backend.domain.CsTxnEntity;
import in.toucan.monitor.engine.Fields;
import in.toucan.monitor.engine.Model;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;

import static in.toucan.monitor.collector.Datasets.CS_TRANSACTIONS;

/**
 * Volume dashboard: per payment channel totals, outcome split, success rate, net, a time series
 * (hourly for one day, daily otherwise) and top merchants.
 *
 * <p>Channel comes from schemeCode values observed in UAT C&S data (23 Nov 2026):
 * VISA, MC, RUPAY, AMEX = Card; UPI_NPCI = UPI; NETBANKING; EMI; WALLET. BNPL has no observed code yet;
 * rows whose scheme is unclassified (e.g. NA on fee/adjustment rows) count only in Total.
 * Outcome comes from txnStatus: SUCCESS = approved, FAILED = declined, REFUND_* = refunded; other values
 * (FEE_SUCCESS, AUTHORIZED, VOIDED, blank) are counted as "other" and shown, never dropped.
 */
@Service
public class VolumeService {

    public record Channel(String key, String label, Set<String> schemes) {
    }

    public static final List<Channel> CHANNELS = List.of(
            new Channel("CARD", "Card", Set.of("VISA", "MC", "RUPAY", "AMEX")),
            new Channel("UPI", "UPI", Set.of("UPI_NPCI")),
            new Channel("NETBANKING", "Net Banking", Set.of("NETBANKING")),
            new Channel("BNPL", "BNPL", Set.of("BNPL")),
            new Channel("EMI", "EMI", Set.of("EMI")),
            new Channel("WALLET", "Wallet", Set.of("WALLET")));

    private final QueryService q;

    public VolumeService(QueryService q) {
        this.q = q;
    }

    public static String channelOf(String scheme) {
        if (scheme == null) return null;
        for (Channel c : CHANNELS) if (c.schemes().contains(scheme.toUpperCase(Locale.ROOT))) return c.key();
        return null;
    }

    static String outcome(String txnStatus) {
        if (txnStatus == null) return "other";
        String s = txnStatus.toUpperCase(Locale.ROOT);
        if (s.equals("SUCCESS")) return "approved";
        if (s.equals("FAILED")) return "declined";
        if (s.startsWith("REFUND")) return "refunded";
        return "other";
    }

    static final class Agg {
        long total, approved, declined, refunded, other;
        BigDecimal amount = BigDecimal.ZERO, approvedAmount = BigDecimal.ZERO, refundedAmount = BigDecimal.ZERO;
        Map<String, Long> otherStatuses = new TreeMap<>();

        void add(CsTxnEntity t) {
            BigDecimal a = Optional.ofNullable(t.txnAmount).orElse(BigDecimal.ZERO);
            total++;
            amount = amount.add(a);
            switch (outcome(t.txnStatus)) {
                case "approved" -> {
                    approved++;
                    approvedAmount = approvedAmount.add(a);
                }
                case "declined" -> declined++;
                case "refunded" -> {
                    refunded++;
                    refundedAmount = refundedAmount.add(a);
                }
                default -> {
                    other++;
                    otherStatuses.merge(String.valueOf(t.txnStatus), 1L, Long::sum);
                }
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", total);
            m.put("amount", amount);
            m.put("approved", approved);
            m.put("declined", declined);
            m.put("refunded", refunded);
            m.put("other", other);
            m.put("otherStatuses", otherStatuses);
            m.put("successRate", approved + declined == 0 ? null
                    : Math.round(10000.0 * approved / (approved + declined)) / 100.0);
            m.put("net", approvedAmount.subtract(refundedAmount));
            return m;
        }
    }

    public Map<String, Object> volume(Filters f) {
        Map<String, Object> out = new LinkedHashMap<>();
        Model.DatasetState st = q.state(CS_TRANSACTIONS);
        out.put("source", q.stateMap(CS_TRANSACTIONS));
        if (!st.available()) {
            out.put("available", false);
            out.put("reason", "Not available: " + Optional.ofNullable(st.error()).orElse("never collected"));
            return out;
        }
        out.put("available", true);
        // Channel filter is applied here (payment mode), not in the query, so Total stays consistent.
        Filters base = new Filters(f.from(), f.to(), f.processor(), f.scheme(), null, f.merchant(), f.txnStatus(),
                f.accountingStatus(), f.ttCode(), f.fileType(), f.q(), f.settlementStatus(), f.paymentReleaseStatus());
        List<CsTxnEntity> txns = q.transactionsAll(base);
        String mode = f.channel();
        if (mode != null && !mode.isBlank()) txns = txns.stream().filter(t -> mode.equalsIgnoreCase(channelOf(t.schemeCode))).toList();

        Map<String, Agg> by = new LinkedHashMap<>();
        CHANNELS.forEach(c -> by.put(c.key(), new Agg()));
        Agg total = new Agg();
        Set<String> seenSchemes = new TreeSet<>();
        Map<String, Long> unclassified = new TreeMap<>();
        boolean hourly = f.from() != null && f.from().equals(f.to());
        Map<String, Map<String, Object>> series = new TreeMap<>();
        Map<String, BigDecimal[]> merchants = new HashMap<>();
        Map<String, String> merchantNames = new HashMap<>();

        for (CsTxnEntity t : txns) {
            total.add(t);
            if (t.schemeCode != null) seenSchemes.add(t.schemeCode);
            String ch = channelOf(t.schemeCode);
            if (ch != null) by.get(ch).add(t);
            else unclassified.merge(String.valueOf(t.schemeCode), 1L, Long::sum);

            String bucket = bucket(t, hourly);
            if (bucket != null) {
                Map<String, Object> row = series.computeIfAbsent(bucket, b -> new LinkedHashMap<>(Map.of("bucket", b)));
                String k = ch == null ? "OTHER" : ch;
                row.merge(k + "_count", 1L, (a, b) -> (Long) a + (Long) b);
                row.merge(k + "_amount", Optional.ofNullable(t.txnAmount).orElse(BigDecimal.ZERO),
                        (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
                row.merge("TOTAL_count", 1L, (a, b) -> (Long) a + (Long) b);
                row.merge("TOTAL_amount", Optional.ofNullable(t.txnAmount).orElse(BigDecimal.ZERO),
                        (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
            }
            if (t.merchantNumber != null && "approved".equals(outcome(t.txnStatus))) {
                BigDecimal[] m = merchants.computeIfAbsent(t.merchantNumber, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                m[0] = m[0].add(BigDecimal.ONE);
                m[1] = m[1].add(Optional.ofNullable(t.txnAmount).orElse(BigDecimal.ZERO));
                if (t.merchantName != null) merchantNames.put(t.merchantNumber, t.merchantName);
            }
        }
        if (hourly) fillHours(series);

        List<Map<String, Object>> cards = new ArrayList<>();
        for (Channel c : CHANNELS) {
            Map<String, Object> m = by.get(c.key()).toMap();
            m.put("key", c.key());
            m.put("label", c.label());
            m.put("schemes", c.schemes());
            boolean observed = c.schemes().stream().anyMatch(seenSchemes::contains);
            m.put("mappingNote", observed ? null : "No " + String.join("/", c.schemes())
                    + " schemeCode seen in collected data for this filter");
            cards.add(m);
        }
        Map<String, Object> tot = total.toMap();
        tot.put("unclassified", unclassified);
        out.put("channels", cards);
        out.put("total", tot);
        out.put("granularity", hourly ? "hour" : "day");
        out.put("series", new ArrayList<>(series.values()));
        out.put("topMerchants", merchants.entrySet().stream()
                .sorted((a, b) -> b.getValue()[1].compareTo(a.getValue()[1])).limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("merchantNumber", e.getKey());
                    m.put("merchantName", merchantNames.getOrDefault(e.getKey(), e.getKey()));
                    m.put("count", e.getValue()[0].longValue());
                    m.put("amount", e.getValue()[1]);
                    return m;
                }).toList());
        out.put("topMerchantsByCount", merchants.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0].compareTo(a.getValue()[0])).limit(10)
                .map(e -> Map.<String, Object>of("merchantNumber", e.getKey(),
                        "merchantName", merchantNames.getOrDefault(e.getKey(), e.getKey()),
                        "count", e.getValue()[0].longValue(), "amount", e.getValue()[1])).toList());
        out.put("declineReasons", Map.of("available", false,
                "reason", "No decline reason code field in the collected C&S transaction data"));
        return out;
    }

    private static String bucket(CsTxnEntity t, boolean hourly) {
        if (!hourly) return t.salesDate == null ? null : t.salesDate.toString();
        return Fields.parseInstant(t.salesDateTime).map(i -> {
            ZonedDateTime z = i.atZone(Fields.zone());
            return String.format("%02d:00", z.getHour());
        }).orElse(null);
    }

    private static void fillHours(Map<String, Map<String, Object>> series) {
        for (int h = 0; h < 24; h++) {
            String k = String.format("%02d:00", h);
            series.computeIfAbsent(k, b -> new LinkedHashMap<>(Map.of("bucket", b, "TOTAL_count", 0L)));
        }
    }

    /** Resolves a frequency preset to a date range (IST). */
    public static LocalDate[] range(String frequency, LocalDate today) {
        return switch (frequency == null ? "" : frequency) {
            case "today" -> new LocalDate[]{today, today};
            case "yesterday" -> new LocalDate[]{today.minusDays(1), today.minusDays(1)};
            case "last7" -> new LocalDate[]{today.minusDays(6), today};
            case "month" -> new LocalDate[]{today.withDayOfMonth(1), today};
            default -> null;
        };
    }
}
