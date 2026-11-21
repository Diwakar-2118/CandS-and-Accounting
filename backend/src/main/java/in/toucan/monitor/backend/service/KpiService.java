package in.toucan.monitor.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import in.toucan.monitor.backend.config.MonitorProperties;
import in.toucan.monitor.backend.domain.CsTxnEntity;
import in.toucan.monitor.backend.domain.RuleResultEntity;
import in.toucan.monitor.engine.Model;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

import static in.toucan.monitor.collector.Datasets.*;

/**
 * Overview KPIs and chart series. A KPI whose source failed on its latest attempt shows
 * value=null, available=false and the last successful retrieval time: never 0.
 */
@Service
public class KpiService {

    private final QueryService q;
    private final MonitorProperties props;

    public KpiService(QueryService q, MonitorProperties props) {
        this.q = q;
        this.props = props;
    }

    private Map<String, Object> kpi(String key, String label, String drill, List<String> datasets,
                                    Supplier<Object> value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("drill", drill);
        List<Map<String, Object>> sources = datasets.stream().map(q::stateMap).toList();
        m.put("sources", sources);
        Optional<Map<String, Object>> down = sources.stream().filter(s -> !(Boolean) s.get("available")).findFirst();
        Instant lastOk = sources.stream().map(s -> (Instant) s.get("lastSuccessAt")).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        m.put("lastSuccessAt", lastOk);
        if (down.isPresent()) {
            m.put("available", false);
            m.put("value", null);
            m.put("reason", "Not available: " + down.get().get("dataset") + " - "
                    + Optional.ofNullable(down.get().get("error")).orElse("never collected"));
            return m;
        }
        Object v = value.get();
        if (v instanceof NotAvailable na) {
            m.put("available", false);
            m.put("value", null);
            m.put("reason", "Not available: " + na.reason());
        } else {
            m.put("available", true);
            m.put("value", v);
        }
        return m;
    }

    record NotAvailable(String reason) {
    }

    public List<Map<String, Object>> overview(Filters f) {
        List<CsTxnEntity> t = q.transactionsAll(f);
        List<Map<String, Object>> k = new ArrayList<>();
        List<String> txnDs = List.of(CS_TRANSACTIONS);
        k.add(kpi("txn.count", "C&S transactions", "/transactions", txnDs, () -> t.size()));
        k.add(kpi("txn.amount", "C&S transaction amount (INR)", "/transactions", txnDs,
                () -> t.stream().map(x -> x.txnAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)));
        for (String s : List.of("SETTLED", "NOT_SETTLED", "SCHEME_REJECT", "DISPUTED")) {
            k.add(kpi("txn.settlementStatus." + s, "settlementStatus = " + s + " (UI: Scheme Status)",
                    "/transactions?settlementStatus=" + s, txnDs,
                    () -> t.stream().filter(x -> s.equals(x.settlementStatus)).count()));
        }
        for (String s : List.of("COMPLETED", "PENDING")) {
            k.add(kpi("txn.paymentRelease." + s, "Payment release " + s, "/transactions?paymentReleaseStatus=" + s,
                    txnDs, () -> t.stream().filter(x -> s.equals(x.paymentReleaseStatus)).count()));
        }
        k.add(kpi("reserve.count", "Rolling-reserve transactions held", "/files?category=rolling-reserve",
                List.of(CS_ROLLING_RESERVE), () -> q.datasetRows(CS_ROLLING_RESERVE).size()));
        k.add(kpi("instant.count", "Instant-settlement exceptions", "/files?category=instant-settlement",
                List.of(CS_INSTANT_EXCEPTIONS), () -> q.datasetRows(CS_INSTANT_EXCEPTIONS).size()));

        LocalDate day = f.to();
        Filters dayF = new Filters(day, day, null, null, null, null, null, null, null, null, null, null, null);
        for (Map.Entry<String, String> e : Map.of("scheme", CS_SCHEME_FILES, "payment", CS_PAYMENT_FILES,
                "processed", CS_PROCESSED_FILES).entrySet()) {
            k.add(kpi("files." + e.getKey(), "Files on " + day + ": " + e.getKey() + " (by status)",
                    "/files?category=" + e.getKey(), List.of(e.getValue()), () -> countBy(q.files(e.getKey(), dayF, null),
                            "dashboardStatus")));
        }
        k.add(kpi("reports.generatedVsExpected", "Reports generated vs expected on " + day, "/files?category=reports",
                List.of(CS_REPORTS), () -> {
                    int generated = q.files("reports", dayF, null).size();
                    if (props.getExpectedDailyReports().isEmpty()) {
                        return new NotAvailable("expected report list not supplied by the business (generated: "
                                + generated + ")");
                    }
                    return Map.of("generated", generated, "expected", props.getExpectedDailyReports().size());
                }));
        k.add(kpi("jv.status", "Journal vouchers by status", "/files?category=jv", List.of(CS_JV),
                () -> countBy(q.files("jv", f, null), "rawStatus")));
        k.add(kpi("bdf.status", "UPI BDF generated", "/files?category=upi-bdf", List.of(CS_UPI_SUMMARY_BDF),
                () -> countBy(q.files("upi-bdf", f, null), "rawStatus")));
        k.add(kpi("accounting.status", "Accounting status of C&S transactions", "/transactions?accountingStatus=",
                List.of(CS_TRANSACTIONS, ACC_RRN_POSTINGS), () -> {
                    Map<String, Long> m = new TreeMap<>();
                    t.forEach(x -> m.merge(Optional.ofNullable(x.accountingStatus).orElse("NOT_CHECKED"), 1L, Long::sum));
                    return m;
                }));
        k.add(kpi("tb.difference", "Trial-balance difference (DR - CR)", "/ledger", List.of(ACC_TRIAL_BALANCE),
                this::tbDifference));
        k.add(kpi("adjustments.unbalanced", "Unbalanced ledger adjustments", "/ledger",
                List.of(ACC_LEDGER_ADJUSTMENTS), () -> ruleCount("R13", "FAIL")));
        k.add(kpi("periods.health", "Period health", "/ledger", List.of(ACC_PERIODS), () -> {
            List<RuleResultEntity> rs = q.latestResults("R14");
            if (rs.isEmpty()) return new NotAvailable("rule R14 has not run yet");
            long fails = rs.stream().filter(r -> !"SUMMARY".equals(r.subjectKey) && "FAIL".equals(r.outcome)).count();
            return fails == 0 ? "OK" : fails + " issue(s)";
        }));
        return k;
    }

    private Object ruleCount(String rule, String outcome) {
        List<RuleResultEntity> rs = q.latestResults(rule);
        if (rs.isEmpty()) return new NotAvailable("rule " + rule + " has not run yet");
        Optional<RuleResultEntity> sum = rs.stream().filter(r -> "SUMMARY".equals(r.subjectKey)).findFirst();
        if (sum.isPresent() && "NOT_EVALUATED".equals(sum.get().outcome)) return new NotAvailable(sum.get().message);
        return rs.stream().filter(r -> !"SUMMARY".equals(r.subjectKey) && outcome.equals(r.outcome)).count();
    }

    private Object tbDifference() {
        List<RuleResultEntity> rs = q.latestResults("R12").stream()
                .filter(r -> !"SUMMARY".equals(r.subjectKey) && r.subjectKey.startsWith("group")).toList();
        if (rs.isEmpty()) return new NotAvailable("no trial-balance evaluation yet");
        List<Map<String, Object>> out = new ArrayList<>();
        for (RuleResultEntity r : rs) {
            JsonNode ev = q.read(r.evidence);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("range", r.subjectKey);
            m.put("outcome", r.outcome);
            m.put("totalDebit", ev.path("details").path("totalDebit").asText(null));
            m.put("totalCredit", ev.path("details").path("totalCredit").asText(null));
            m.put("difference", ev.path("details").path("difference").asText(null));
            out.add(m);
        }
        return out;
    }

    static Map<String, Long> countBy(List<Map<String, Object>> rows, String key) {
        Map<String, Long> m = new TreeMap<>();
        rows.forEach(r -> m.merge(String.valueOf(r.get(key)), 1L, Long::sum));
        return m;
    }

    // ---------------------------------------------------------------- charts

    public Map<String, Object> charts(Filters f) {
        List<CsTxnEntity> t = q.transactionsAll(f);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> avail = new LinkedHashMap<>();
        for (String ds : List.of(CS_TRANSACTIONS, ACC_RRN_POSTINGS, CS_SCHEME_FILES)) {
            Model.DatasetState s = q.state(ds);
            avail.put(ds, Map.of("available", s.available(), "lastSuccessAt", String.valueOf(s.lastSuccessAt())));
        }
        out.put("availability", avail);

        // Volume and amount by day and processor
        Map<String, Map<String, Object>> vol = new TreeMap<>();
        for (CsTxnEntity x : t) {
            if (x.salesDate == null) continue;
            Map<String, Object> row = vol.computeIfAbsent(x.salesDate.toString(), d -> new LinkedHashMap<>(Map.of("date", d)));
            String p = Optional.ofNullable(x.platformType).orElse("UNKNOWN");
            row.merge(p + "_count", 1L, (a, b) -> (Long) a + (Long) b);
            row.merge(p + "_amount", Optional.ofNullable(x.txnAmount).orElse(BigDecimal.ZERO),
                    (a, b) -> ((BigDecimal) a).add((BigDecimal) b));
        }
        out.put("volumeByDayProcessor", new ArrayList<>(vol.values()));

        // Accounting status split
        Map<String, Long> acc = new TreeMap<>();
        t.forEach(x -> acc.merge(Optional.ofNullable(x.accountingStatus).orElse("NOT_CHECKED"), 1L, Long::sum));
        out.put("accountingStatus", acc.entrySet().stream().map(e -> Map.of("status", e.getKey(), "count", e.getValue())).toList());

        // Daily C&S vs Accounting (transactions vs transactions with postings)
        Map<String, long[]> daily = new TreeMap<>();
        for (CsTxnEntity x : t) {
            if (x.salesDate == null) continue;
            long[] a = daily.computeIfAbsent(x.salesDate.toString(), d -> new long[2]);
            a[0]++;
            if ("COMPLETED".equals(x.accountingStatus) || "FAILED".equals(x.accountingStatus)) a[1]++;
        }
        out.put("dailyPostings", daily.entrySet().stream().map(e -> Map.of("date", e.getKey(), "cs", e.getValue()[0],
                "accounting", e.getValue()[1])).toList());

        // Files by status
        Map<String, Map<String, Object>> files = new TreeMap<>();
        for (String cat : List.of("scheme", "payment", "reports", "processed")) {
            for (Map<String, Object> r : q.files(cat, f, null)) {
                Map<String, Object> row = files.computeIfAbsent(cat, c -> new LinkedHashMap<>(Map.of("category", c)));
                row.merge(String.valueOf(r.get("dashboardStatus")), 1L, (a, b) -> (Long) a + (Long) b);
            }
        }
        out.put("filesByStatus", new ArrayList<>(files.values()));

        // Failures by processor and rule
        Map<String, Map<String, Object>> fails = new TreeMap<>();
        for (RuleResultEntity r : q.latestResults(null)) {
            if ("SUMMARY".equals(r.subjectKey) || !(r.outcome.equals("FAIL") || r.outcome.equals("MISSING"))) continue;
            String p = Optional.ofNullable(r.processor).orElse("N/A");
            Map<String, Object> row = fails.computeIfAbsent(r.ruleId, id -> new LinkedHashMap<>(Map.of("rule", id)));
            row.merge(p, 1L, (a, b) -> (Long) a + (Long) b);
        }
        out.put("failuresByRuleProcessor", new ArrayList<>(fails.values()));
        return out;
    }
}
