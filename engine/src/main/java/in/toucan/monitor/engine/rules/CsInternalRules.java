package in.toucan.monitor.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import in.toucan.monitor.engine.Fields;
import in.toucan.monitor.engine.Model.Evidence;
import in.toucan.monitor.engine.Model.ExpectedFile;
import in.toucan.monitor.engine.Model.Outcome;
import in.toucan.monitor.engine.Model.Rec;
import in.toucan.monitor.engine.Model.RuleResult;
import in.toucan.monitor.engine.Rule;
import in.toucan.monitor.engine.RuleContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static in.toucan.monitor.collector.Datasets.*;

/** Rules 1-7: checks inside C&S. */
public final class CsInternalRules {

    private CsInternalRules() {
    }

    static final List<String> FEE_FIELDS = List.of("txn.amount", "txn.mdrAmount", "txn.mdrCgst", "txn.mdrSgst",
            "txn.feeAmount", "txn.feeCgst", "txn.feeSgst", "txn.paymentAmount");

    /** R01: payment amount = txn amount - MDR - GST on MDR - fee - GST on fee. */
    public static final class FeeArithmetic implements Rule {
        public String id() { return "R01"; }
        public String name() { return "Fee arithmetic per transaction"; }
        public String category() { return "CS_INTERNAL"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            List<String> unconfirmed = FEE_FIELDS.stream().filter(k -> !f.configured(k)).toList();
            if (!unconfirmed.isEmpty()) {
                return r.notEvaluated("Field names not confirmed for " + unconfirmed
                        + " (seen on the detail page only; confirm via runtime discovery and set monitor.fields)",
                        List.of(ctx.datasetRef(CS_TRANSACTIONS)));
            }
            Optional<String> down = ctx.unavailable(CS_TRANSACTIONS);
            if (down.isPresent()) return r.notEvaluated(down.get(), List.of(ctx.datasetRef(CS_TRANSACTIONS)));
            int skipped = 0;
            for (Rec rec : ctx.records(CS_TRANSACTIONS)) {
                JsonNode t = rec.payload();
                Map<String, BigDecimal> v = new HashMap<>();
                for (String k : FEE_FIELDS) f.decimal(t, k).ifPresent(x -> v.put(k, x));
                if (v.size() < FEE_FIELDS.size()) {
                    skipped++;
                    continue;
                }
                BigDecimal expected = v.get("txn.amount").subtract(v.get("txn.mdrAmount"))
                        .subtract(v.get("txn.mdrCgst")).subtract(v.get("txn.mdrSgst"))
                        .subtract(v.get("txn.feeAmount")).subtract(v.get("txn.feeCgst")).subtract(v.get("txn.feeSgst"));
                BigDecimal actual = v.get("txn.paymentAmount");
                if (expected.subtract(actual).abs().compareTo(ctx.config().tolerance()) > 0) {
                    r.add(rec.ref().recordKey(), Outcome.FAIL, "Payment amount " + actual + " != computed " + expected,
                            ctx.processor(t), f.date(t, "txn.salesDateTime").orElse(null), null, null,
                            new Evidence(expected.toPlainString(), actual.toPlainString(), List.of(rec.ref()),
                                    Map.of("components", v.toString(), "rrn", String.valueOf(f.text(t, "txn.rrn")))));
                } else {
                    r.pass();
                }
            }
            return r.finish(skipped > 0 ? skipped + " transactions lacked fee fields." : null,
                    List.of(ctx.datasetRef(CS_TRANSACTIONS)));
        }
    }

    /** R02: a payment's amount equals the sum of its transactions' net amounts. */
    public static final class PaymentEqualsSumOfTxns implements Rule {
        public String id() { return "R02"; }
        public String name() { return "Payment amount = sum of transaction net amounts"; }
        public String category() { return "CS_INTERNAL"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            List<in.toucan.monitor.engine.Model.SourceRef> refs = List.of(ctx.datasetRef(CS_PAYMENTS_CREDIT),
                    ctx.datasetRef(CS_PAYMENT_TXNS));
            if (!f.configured("payment.amount")) {
                return r.notEvaluated("Payment amount field not confirmed (set monitor.fields.payment.amount)", refs);
            }
            Optional<String> down = ctx.unavailable(CS_PAYMENT_TXNS);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            Map<String, Rec> txnSnapshots = new HashMap<>();
            for (Rec s : ctx.records(CS_PAYMENT_TXNS)) {
                JsonNode q = s.payload().path("query");
                txnSnapshots.put(q.path("runNumber").asText() + "|" + q.path("merchantNumber").asText() + "|"
                        + q.path("dbOrcrToMerchant").asText(), s);
            }
            for (String ds : List.of(CS_PAYMENTS_CREDIT, CS_PAYMENTS_DEBIT)) {
                String side = ds.equals(CS_PAYMENTS_CREDIT) ? "CREDIT" : "DEBIT";
                for (Rec p : ctx.records(ds)) {
                    JsonNode pay = p.payload();
                    String key = f.text(pay, "payment.runNumber") + "|" + f.text(pay, "payment.mid") + "|" + side;
                    Rec snap = txnSnapshots.get(key);
                    Optional<BigDecimal> amount = f.decimal(pay, "payment.amount");
                    if (snap == null || amount.isEmpty()) continue;
                    BigDecimal sum = BigDecimal.ZERO;
                    int n = 0;
                    for (JsonNode t : snap.payload().path("rows")) {
                        Optional<BigDecimal> net = f.decimal(t, "txn.netAmount");
                        if (net.isPresent()) {
                            sum = sum.add(net.get());
                            n++;
                        }
                    }
                    if (sum.subtract(amount.get()).abs().compareTo(ctx.config().tolerance()) > 0) {
                        r.add(p.ref().recordKey(), Outcome.FAIL, "Payment " + amount.get() + " != sum of " + n
                                        + " transactions " + sum, null, null, null, null,
                                new Evidence(amount.get().toPlainString(), sum.toPlainString(),
                                        List.of(p.ref(), snap.ref()), Map.of("paymentKey", key, "txnCount", n)));
                    } else {
                        r.pass();
                    }
                }
            }
            return r.finish(null, refs);
        }
    }

    /** R03: paymentReleaseStatusEnum=COMPLETED but no payment record for run+MID(+side). */
    public static final class ReleasedWithoutPayment implements Rule {
        public String id() { return "R03"; }
        public String name() { return "Released transaction has a payment record"; }
        public String category() { return "CS_INTERNAL"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(CS_TRANSACTIONS), ctx.datasetRef(CS_PAYMENTS_CREDIT),
                    ctx.datasetRef(CS_PAYMENTS_DEBIT));
            Optional<String> down = ctx.unavailable(CS_TRANSACTIONS, CS_PAYMENTS_CREDIT, CS_PAYMENTS_DEBIT);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            if (ctx.records(CS_PAYMENTS_CREDIT).isEmpty() && ctx.records(CS_PAYMENTS_DEBIT).isEmpty()) {
                // Seen on UAT: getAllMerPay returns no rows for some users. Matching against an empty list
                // would flag every released transaction, which says nothing about the transactions.
                return r.notEvaluated("The payments list returned no records, so released transactions cannot be "
                        + "matched to payments (check the account's access to Payments)", refs);
            }
            boolean partial = ctx.data().state(CS_PAYMENTS_CREDIT).truncated()
                    || ctx.data().state(CS_PAYMENTS_DEBIT).truncated();
            Set<String> withSide = new HashSet<>();
            Set<String> noSide = new HashSet<>();
            for (String ds : List.of(CS_PAYMENTS_CREDIT, CS_PAYMENTS_DEBIT)) {
                String side = ds.equals(CS_PAYMENTS_CREDIT) ? "CREDIT" : "DEBIT";
                for (Rec p : ctx.records(ds)) {
                    String k = f.text(p.payload(), "payment.runNumber") + "|" + f.text(p.payload(), "payment.mid");
                    noSide.add(k);
                    withSide.add(k + "|" + side);
                }
            }
            for (Rec rec : ctx.records(CS_TRANSACTIONS)) {
                JsonNode t = rec.payload();
                if (!"COMPLETED".equalsIgnoreCase(f.text(t, "txn.paymentReleaseStatus"))) continue;
                String run = f.text(t, "txn.runNumber");
                String mid = f.text(t, "txn.mid");
                String side = f.text(t, "txn.dbOrCr");
                boolean found = side == null ? noSide.contains(run + "|" + mid)
                        : withSide.contains(run + "|" + mid + "|" + side.toUpperCase());
                if (found) {
                    r.pass();
                } else {
                    r.add(rec.ref().recordKey(), partial ? Outcome.WARN : Outcome.FAIL,
                            "COMPLETED release but no payment for run " + run + ", MID " + mid
                                    + (partial ? " (payment list was truncated; verify)" : ""),
                            ctx.processor(t), f.date(t, "txn.salesDateTime").orElse(null), null, null,
                            new Evidence("payment for " + run + "|" + mid + "|" + side, "none", List.of(rec.ref()),
                                    Map.of("rrn", String.valueOf(f.text(t, "txn.rrn")))));
                }
            }
            return r.finish(null, refs);
        }
    }

    /** R04: JV not completed past its window. */
    public static final class JvNotTriggered implements Rule {
        public String id() { return "R04"; }
        public String name() { return "Journal voucher completed within window"; }
        public String category() { return "CS_INTERNAL"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(CS_JV));
            Optional<String> down = ctx.unavailable(CS_JV);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            Integer window = ctx.config().jvWindowHours();
            Instant now = ctx.clock().instant();
            for (Rec rec : ctx.records(CS_JV)) {
                JsonNode jv = rec.payload();
                String status = f.text(jv, "jv.status");
                if (status != null && status.trim().equalsIgnoreCase("Completed")) {
                    r.pass();
                    continue;
                }
                LocalDate d = f.date(jv, "jv.date").orElse(null);
                Evidence ev = new Evidence("Completed", String.valueOf(status), List.of(rec.ref()),
                        Map.of("jvName", String.valueOf(f.text(jv, "jv.name")), "file", String.valueOf(f.text(jv, "jv.file"))));
                if (window == null) {
                    r.add(rec.ref().recordKey(), Outcome.WARN, "JV status '" + status
                            + "' (JV window not supplied by business, cannot decide overdue)", null, d, null, null, ev);
                } else if (d != null && ZonedDateTime.of(d.atStartOfDay(), Fields.zone()).plusHours(window)
                        .toInstant().isBefore(now)) {
                    r.add(rec.ref().recordKey(), Outcome.FAIL, "JV status '" + status + "' past its " + window
                            + "h window", null, d, null, null, ev);
                } else {
                    r.add(rec.ref().recordKey(), Outcome.PENDING, "JV status '" + status + "', within window", null,
                            d, null, null, ev);
                }
            }
            return r.finish(null, refs);
        }
    }

    /** R05: file statuses and expected-file calendar. */
    public static final class FileHealth implements Rule {
        public String id() { return "R05"; }
        public String name() { return "Files processed successfully and none missing"; }
        public String category() { return "CS_INTERNAL"; }

        static final List<String> FILE_DATASETS = List.of(CS_SCHEME_FILES, CS_PAYMENT_FILES, CS_REPORTS,
                CS_PROCESSED_FILES);

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            List<in.toucan.monitor.engine.Model.SourceRef> refs = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (String ds : FILE_DATASETS) {
                refs.add(ctx.datasetRef(ds));
                Optional<String> down = ctx.unavailable(ds);
                if (down.isPresent()) {
                    r.add(ds, Outcome.NOT_EVALUATED, down.get(), null, null, null, null,
                            Evidence.of(null, null, List.of(ctx.datasetRef(ds))));
                    continue;
                }
                for (Rec rec : ctx.records(ds)) {
                    JsonNode file = rec.payload();
                    LocalDate d = f.date(file, "file.date").orElse(null);
                    if (d != null && !ctx.inWindow(d)) continue;
                    String status = f.text(file, "file.status");
                    if (ctx.config().isFileSuccess(status)) {
                        r.pass();
                    } else {
                        r.add(rec.ref().recordKey(), Outcome.FAIL, "File '" + f.text(file, "file.name")
                                        + "' status " + status, null, d, null, null,
                                new Evidence("*SUCCESSFULLY*", String.valueOf(status), List.of(rec.ref()),
                                        Map.of("flag", String.valueOf(f.text(file, "file.flag")))));
                    }
                }
            }
            List<ExpectedFile> cal = ctx.config().calendar();
            if (cal.isEmpty()) {
                notes.add("Missing-file check not run: expected-file calendar not supplied by the business.");
            } else {
                ZonedDateTime now = ZonedDateTime.now(ctx.clock().withZone(Fields.zone()));
                for (ExpectedFile e : cal) {
                    Pattern p = Pattern.compile(e.fileNameRegex());
                    for (LocalDate d = ctx.from(); !d.isAfter(ctx.to()); d = d.plusDays(1)) {
                        if (!e.days().contains(d.getDayOfWeek())) continue;
                        if (ZonedDateTime.of(d, e.dueBy(), Fields.zone()).isAfter(now)) continue;
                        LocalDate day = d;
                        if (ctx.unavailable(e.dataset()).isPresent()) continue;
                        boolean present = ctx.records(e.dataset()).stream().anyMatch(rec -> {
                            String n = f.text(rec.payload(), "file.name");
                            return n != null && p.matcher(n).matches()
                                    && day.equals(f.date(rec.payload(), "file.date").orElse(null));
                        });
                        if (present) r.pass();
                        else r.add(e.dataset() + "|" + e.fileNameRegex() + "|" + d, Outcome.MISSING,
                                "Expected file matching " + e.fileNameRegex() + " not found for " + d, null, d, null,
                                null, Evidence.of("file due by " + e.dueBy(), "absent", List.of(ctx.datasetRef(e.dataset()))));
                    }
                }
            }
            return r.finish(String.join(" ", notes), refs);
        }
    }

    /** R06: UPI summary rows whose BDF was not generated. */
    public static final class UpiBdfGenerated implements Rule {
        public String id() { return "R06"; }
        public String name() { return "UPI summary BDF generated"; }
        public String category() { return "CS_INTERNAL"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(CS_UPI_SUMMARY_BDF));
            Optional<String> down = ctx.unavailable(CS_UPI_SUMMARY_BDF);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            for (Rec rec : ctx.records(CS_UPI_SUMMARY_BDF)) {
                String g = f.text(rec.payload(), "bdf.generated");
                LocalDate d = f.date(rec.payload(), "bdf.date").orElse(null);
                if ("true".equalsIgnoreCase(g)) r.pass();
                else r.add(rec.ref().recordKey(), Outcome.FAIL, "BDF not generated for "
                                + f.text(rec.payload(), "bdf.file") + " (value: " + (g == null ? "blank" : g) + ")",
                        "UPI", d, null, null, Evidence.of("TRUE", g == null ? "blank" : g, List.of(rec.ref())));
            }
            return r.finish(null, refs);
        }
    }

    /** R07: masked-data problems ("Decryption failed") in any collected payload. */
    public static final class DecryptionFailures implements Rule {
        public String id() { return "R07"; }
        public String name() { return "No 'Decryption failed' values"; }
        public String category() { return "CS_INTERNAL"; }

        static final List<String> SCANNED = List.of(CS_PAYMENTS_CREDIT, CS_PAYMENTS_DEBIT, CS_JV, CS_PAYMENT_FILES,
                CS_TRANSACTIONS);

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            List<in.toucan.monitor.engine.Model.SourceRef> refs = new ArrayList<>();
            for (String ds : SCANNED) {
                refs.add(ctx.datasetRef(ds));
                for (Rec rec : ctx.records(ds)) {
                    List<String> hits = new ArrayList<>();
                    scan(rec.payload(), "", hits);
                    if (hits.isEmpty()) r.pass();
                    else r.add(rec.ref().recordKey(), Outcome.FAIL, "'Decryption failed' in " + hits, null, null,
                            null, null, new Evidence("decrypted value", "Decryption failed", List.of(rec.ref()),
                                    Map.of("fields", hits, "dataset", ds)));
                }
            }
            return r.finish(null, refs);
        }

        static void scan(JsonNode n, String path, List<String> hits) {
            if (n.isTextual() && n.asText().toLowerCase().contains("decryption failed")) hits.add(path);
            else if (n.isObject()) n.fields().forEachRemaining(e -> scan(e.getValue(),
                    path.isEmpty() ? e.getKey() : path + "." + e.getKey(), hits));
            else if (n.isArray()) for (int i = 0; i < n.size(); i++) scan(n.get(i), path + "[" + i + "]", hits);
        }
    }
}
