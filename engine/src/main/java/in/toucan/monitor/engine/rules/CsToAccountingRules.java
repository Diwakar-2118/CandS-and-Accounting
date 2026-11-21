package in.toucan.monitor.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import in.toucan.monitor.engine.Fields;
import in.toucan.monitor.engine.Model.Evidence;
import in.toucan.monitor.engine.Model.GlLine;
import in.toucan.monitor.engine.Model.MappingStatus;
import in.toucan.monitor.engine.Model.Outcome;
import in.toucan.monitor.engine.Model.Rec;
import in.toucan.monitor.engine.Model.RuleResult;
import in.toucan.monitor.engine.Model.Side;
import in.toucan.monitor.engine.Model.TtMapping;
import in.toucan.monitor.engine.Rule;
import in.toucan.monitor.engine.RuleContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static in.toucan.monitor.collector.Datasets.ACC_RRN_POSTINGS;
import static in.toucan.monitor.collector.Datasets.CS_TRANSACTIONS;

/**
 * Rules 8-11: did C&S transactions reach Accounting, correctly and once?
 *
 * <p>Phase 1 found {@code /api/accounting/searchAllEntries {rrn}} (the Accounting-Entry RRN search),
 * which returns per-transaction postings (subModule, ledgerName, debit, credit) and a {@code netZero}
 * flag. These rules therefore work per transaction, for every C&S transaction whose RRN was looked up.
 */
public final class CsToAccountingRules {

    private CsToAccountingRules() {
    }

    static Map<String, Rec> postingsByRrn(RuleContext ctx) {
        Map<String, Rec> m = new HashMap<>();
        for (Rec s : ctx.records(ACC_RRN_POSTINGS)) {
            String rrn = s.payload().path("query").path("rrn").asText(null);
            if (rrn != null) m.put(rrn, s);
        }
        return m;
    }

    record Totals(BigDecimal debit, BigDecimal credit, int lines) {
    }

    static Totals totals(Fields f, JsonNode snapshot) {
        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        int n = 0;
        for (JsonNode row : snapshot.path("rows")) {
            d = d.add(f.decimal(row, "posting.debit").orElse(BigDecimal.ZERO));
            c = c.add(f.decimal(row, "posting.credit").orElse(BigDecimal.ZERO));
            n++;
        }
        return new Totals(d, c, n);
    }

    /** R08: transaction posted in Accounting and its postings balance (debit = credit, netZero). */
    public static final class PostedAndBalanced implements Rule {
        public String id() { return "R08"; }
        public String name() { return "C&S transaction posted and balanced in Accounting"; }
        public String category() { return "CS_TO_ACCOUNTING"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(CS_TRANSACTIONS), ctx.datasetRef(ACC_RRN_POSTINGS));
            Optional<String> down = ctx.unavailable(CS_TRANSACTIONS, ACC_RRN_POSTINGS);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            Map<String, Rec> postings = postingsByRrn(ctx);
            int notLookedUp = 0;
            for (Rec rec : ctx.records(CS_TRANSACTIONS)) {
                JsonNode t = rec.payload();
                String rrn = f.text(t, "txn.rrn");
                Rec snap = rrn == null ? null : postings.get(rrn);
                if (snap == null) {
                    notLookedUp++;
                    continue;
                }
                Totals tot = totals(f, snap.payload());
                if (tot.lines() == 0) continue; // R10 decides pending vs missing
                JsonNode nz = snap.payload().path("netZero");
                boolean balanced = tot.debit().subtract(tot.credit()).abs().compareTo(ctx.config().tolerance()) <= 0;
                boolean netZeroFalse = nz.isBoolean() && !nz.asBoolean();
                LocalDate d = f.date(t, "txn.salesDateTime").orElse(null);
                if (balanced && !netZeroFalse) {
                    r.add(rec.ref().recordKey(), Outcome.PASS, "Posted: " + tot.lines() + " lines, DR=CR=" + tot.debit(),
                            ctx.processor(t), d, null, null,
                            Evidence.of("DR = CR", tot.debit() + " / " + tot.credit(), List.of(rec.ref(), snap.ref())));
                } else {
                    r.add(rec.ref().recordKey(), Outcome.FAIL, "Postings unbalanced: DR " + tot.debit() + " vs CR "
                                    + tot.credit() + (netZeroFalse ? " (Accounting netZero=false)" : ""),
                            ctx.processor(t), d, null, null,
                            new Evidence("DR = CR", tot.debit() + " / " + tot.credit(), List.of(rec.ref(), snap.ref()),
                                    Map.of("rrn", rrn, "netZero", nz.isMissingNode() ? "absent" : nz.asText())));
                }
            }
            return r.finish(notLookedUp > 0 ? notLookedUp + " transactions not yet looked up in Accounting." : null,
                    refs);
        }
    }

    /** R09: each posted ledger and side must be a configured G/L line of a CONFIRMED transaction type. */
    public static final class PostingMatchesTtMapping implements Rule {
        public String id() { return "R09"; }
        public String name() { return "Posted ledger matches transaction-type mapping"; }
        public String category() { return "CS_TO_ACCOUNTING"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(ACC_RRN_POSTINGS),
                    ctx.datasetRef(in.toucan.monitor.collector.Datasets.ACC_TXN_TYPES));
            if (!f.configured("posting.ttCode")) {
                return r.notEvaluated("The posting field that carries the TT code is not confirmed "
                        + "(candidate: subModule). Set monitor.fields.posting.ttCode after checking runtime discovery.", refs);
            }
            Optional<String> down = ctx.unavailable(ACC_RRN_POSTINGS);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            Map<String, TtMapping> byCode = new HashMap<>();
            ctx.data().mappings().forEach(m -> byCode.put(m.code(), m));
            for (Rec snap : ctx.records(ACC_RRN_POSTINGS)) {
                String rrn = snap.payload().path("query").path("rrn").asText();
                int i = 0;
                for (JsonNode row : snap.payload().path("rows")) {
                    String subject = rrn + "#" + i++;
                    String tt = f.text(row, "posting.ttCode");
                    String ledger = f.text(row, "posting.ledger");
                    BigDecimal dr = f.decimal(row, "posting.debit").orElse(BigDecimal.ZERO);
                    Side side = dr.signum() != 0 ? Side.DR : Side.CR;
                    LocalDate d = f.date(row, "posting.dateTime").orElse(null);
                    TtMapping m = tt == null ? null : byCode.get(tt);
                    if (m == null) {
                        r.add(subject, Outcome.NOT_EVALUATED, "No Accounting transaction type '" + tt + "'", null, d,
                                tt, ledger, Evidence.of("known TT code", String.valueOf(tt), List.of(snap.ref())));
                    } else if (m.status() != MappingStatus.CONFIRMED) {
                        r.add(subject, Outcome.NOT_EVALUATED, "Mapping not confirmed", null, d, tt, ledger,
                                Evidence.of("confirmed mapping", "UNCONFIRMED", List.of(snap.ref(), m.source())));
                    } else if (m.lines().contains(new GlLine(ledger, side))) {
                        r.pass();
                    } else {
                        r.add(subject, Outcome.FAIL, side + " " + ledger + " is not configured for " + tt, null, d, tt,
                                ledger, new Evidence(m.lines().toString(), side + " " + ledger,
                                        List.of(snap.ref(), m.source()), Map.of("rrn", rrn)));
                    }
                }
            }
            return r.finish(null, refs);
        }
    }

    /** R10: not posted yet -> PENDING inside SLA, MISSING after. Never MISSING immediately. */
    public static final class PendingVsMissing implements Rule {
        public String id() { return "R10"; }
        public String name() { return "Accounting pending vs missing (SLA)"; }
        public String category() { return "CS_TO_ACCOUNTING"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(CS_TRANSACTIONS), ctx.datasetRef(ACC_RRN_POSTINGS));
            Optional<String> down = ctx.unavailable(CS_TRANSACTIONS, ACC_RRN_POSTINGS);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            Map<String, Rec> postings = postingsByRrn(ctx);
            Instant now = ctx.clock().instant();
            for (Rec rec : ctx.records(CS_TRANSACTIONS)) {
                JsonNode t = rec.payload();
                String rrn = f.text(t, "txn.rrn");
                Rec snap = rrn == null ? null : postings.get(rrn);
                if (snap == null || totals(f, snap.payload()).lines() > 0) continue;
                String code = f.text(t, "txn.transactionCode");
                Integer sla = ctx.config().slaHoursFor(code);
                Optional<Instant> at = Fields.parseInstant(f.text(t, "txn.salesDateTime"));
                LocalDate d = f.date(t, "txn.salesDateTime").orElse(null);
                Map<String, Object> det = new LinkedHashMap<>();
                det.put("rrn", rrn);
                det.put("transactionCode", String.valueOf(code));
                det.put("slaHours", sla == null ? "not configured" : sla);
                Evidence ev = new Evidence("postings for RRN", "none", List.of(rec.ref(), snap.ref()), det);
                if (sla == null || at.isEmpty()) {
                    r.add(rec.ref().recordKey(), Outcome.PENDING, "Not posted; " + (sla == null
                            ? "no SLA configured for " + code : "transaction time unreadable"), ctx.processor(t), d, null,
                            null, ev);
                } else if (Duration.between(at.get(), now).toHours() < sla) {
                    r.add(rec.ref().recordKey(), Outcome.PENDING, "Not posted; within " + sla + "h SLA",
                            ctx.processor(t), d, null, null, ev);
                } else {
                    r.add(rec.ref().recordKey(), Outcome.MISSING, "Not posted " + sla + "h after the transaction",
                            ctx.processor(t), d, null, null, ev);
                }
            }
            return r.finish(null, refs);
        }
    }

    /** R11: the same posting line recorded more than once for one RRN. */
    public static final class DuplicatePostings implements Rule {
        public String id() { return "R11"; }
        public String name() { return "No duplicate postings"; }
        public String category() { return "CS_TO_ACCOUNTING"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(ACC_RRN_POSTINGS));
            Optional<String> down = ctx.unavailable(ACC_RRN_POSTINGS);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            for (Rec snap : ctx.records(ACC_RRN_POSTINGS)) {
                String rrn = snap.payload().path("query").path("rrn").asText();
                Map<String, Integer> seen = new LinkedHashMap<>();
                for (JsonNode row : snap.payload().path("rows")) {
                    String k = f.text(row, "posting.subModule") + "|" + f.text(row, "posting.ledger") + "|"
                            + f.decimal(row, "posting.debit").map(BigDecimal::stripTrailingZeros).orElse(null) + "|"
                            + f.decimal(row, "posting.credit").map(BigDecimal::stripTrailingZeros).orElse(null);
                    seen.merge(k, 1, Integer::sum);
                }
                if (seen.isEmpty()) continue;
                boolean dup = false;
                for (var e : seen.entrySet()) {
                    if (e.getValue() > 1) {
                        dup = true;
                        r.add(rrn + "|" + e.getKey(), Outcome.FAIL, "Posting repeated " + e.getValue() + "x for RRN "
                                        + rrn + ": " + e.getKey(), null, null, null, e.getKey().split("\\|")[1],
                                new Evidence("1", String.valueOf(e.getValue()), List.of(snap.ref()), Map.of("rrn", rrn)));
                    }
                }
                if (!dup) r.pass();
            }
            return r.finish(null, refs);
        }
    }
}
