package in.toucan.monitor.backend.service;

import in.toucan.monitor.backend.domain.CsTxnEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Global dashboard filters. Channel is derived only from observed values: scheme codes (UPI_NPCI vs
 * card schemes), fee transaction codes and transactionStatus HOLD.
 */
public record Filters(LocalDate from, LocalDate to, String processor, String scheme, String channel,
                      String merchant, String txnStatus, String accountingStatus, String ttCode, String fileType,
                      String q, String settlementStatus, String paymentReleaseStatus) {

    public static final List<String> CARD_SCHEMES = List.of("VISA", "MC", "RUPAY");
    public static final List<String> FEE_CODES = List.of("SETTLEMENT_FEE", "RENT_FEE", "MISC_FEE", "UPFRONT_RENT_FEE",
            "SETUP_FEE");
    public static final List<String> CHANNELS = List.of("CARDS", "UPI", "FEE", "HOLD");

    public Specification<CsTxnEntity> spec() {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("salesDate"), from));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.get("salesDate"), to));
            if (has(processor)) p.add(cb.equal(root.get("platformType"), processor));
            if (has(scheme)) p.add(cb.equal(root.get("schemeCode"), scheme));
            if (has(merchant)) p.add(cb.or(cb.equal(root.get("merchantNumber"), merchant),
                    cb.like(cb.upper(root.get("merchantName")), "%" + merchant.toUpperCase(Locale.ROOT) + "%")));
            if (has(txnStatus)) p.add(cb.equal(root.get("txnStatus"), txnStatus));
            if (has(settlementStatus)) p.add(cb.equal(root.get("settlementStatus"), settlementStatus));
            if (has(paymentReleaseStatus)) p.add(cb.equal(root.get("paymentReleaseStatus"), paymentReleaseStatus));
            if (has(accountingStatus)) p.add(cb.equal(root.get("accountingStatus"), accountingStatus));
            if (has(channel)) {
                switch (channel.toUpperCase(Locale.ROOT)) {
                    case "UPI" -> p.add(cb.like(root.get("schemeCode"), "UPI%"));
                    case "CARDS" -> p.add(root.get("schemeCode").in(CARD_SCHEMES));
                    case "FEE" -> p.add(root.get("transactionCode").in(FEE_CODES));
                    case "HOLD" -> p.add(cb.equal(root.get("transactionStatus"), "HOLD"));
                    default -> { }
                }
            }
            if (has(q)) {
                String v = q.trim();
                List<Predicate> any = new ArrayList<>();
                for (String f : List.of("rrn", "arn", "invoiceNumber", "merchantNumber", "terminalNumber", "runNumber",
                        "naturalKey")) {
                    any.add(cb.equal(root.get(f), v));
                }
                try {
                    any.add(cb.equal(root.get("txnAmount"), new java.math.BigDecimal(v)));
                } catch (NumberFormatException ignored) {
                    // not an amount
                }
                p.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    static boolean has(String s) {
        return s != null && !s.isBlank();
    }
}
