package in.toucan.monitor.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import in.toucan.monitor.engine.Fields;
import in.toucan.monitor.engine.Model.Evidence;
import in.toucan.monitor.engine.Model.Outcome;
import in.toucan.monitor.engine.Model.Rec;
import in.toucan.monitor.engine.Model.RuleResult;
import in.toucan.monitor.engine.Rule;
import in.toucan.monitor.engine.RuleContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static in.toucan.monitor.collector.Datasets.*;

/** Rules 12-15: checks inside Accounting. */
public final class AccountingInternalRules {

    private AccountingInternalRules() {
    }

    /** R12: trial balance total debit = total credit, per collected query (date range + category). */
    public static final class TrialBalanceBalanced implements Rule {
        public String id() { return "R12"; }
        public String name() { return "Trial balance: total debit = total credit"; }
        public String category() { return "ACCOUNTING_INTERNAL"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(ACC_TRIAL_BALANCE));
            Optional<String> down = ctx.unavailable(ACC_TRIAL_BALANCE);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            for (Rec snap : ctx.records(ACC_TRIAL_BALANCE)) {
                JsonNode q = snap.payload().path("query");
                BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
                int rows = 0;
                for (JsonNode row : snap.payload().path("rows")) {
                    dr = dr.add(f.decimal(row, "tb.debit").orElse(BigDecimal.ZERO));
                    cr = cr.add(f.decimal(row, "tb.credit").orElse(BigDecimal.ZERO));
                    rows++;
                }
                String subject = q.path("category").asText() + " " + q.path("fromDate").asText() + ".."
                        + q.path("toDate").asText();
                if (rows == 0) {
                    r.add(subject, Outcome.NOT_EVALUATED, "Trial balance returned no rows", null, null, null, null,
                            Evidence.of(null, "0 rows", List.of(snap.ref())));
                    continue;
                }
                BigDecimal diff = dr.subtract(cr);
                Map<String, Object> det = new LinkedHashMap<>();
                det.put("totalDebit", dr.toPlainString());
                det.put("totalCredit", cr.toPlainString());
                det.put("difference", diff.toPlainString());
                det.put("rows", rows);
                det.put("query", q.toString());
                LocalDate to = Fields.parseDate(q.path("toDate").asText()).orElse(null);
                if (diff.abs().compareTo(ctx.config().tolerance()) <= 0) {
                    r.add(subject, Outcome.PASS, "Balanced: " + dr.toPlainString(), null, to, null, null,
                            new Evidence("DR = CR", dr + " / " + cr, List.of(snap.ref()), det));
                } else {
                    r.add(subject, Outcome.FAIL, "Out of balance by " + diff.toPlainString() + " (DR " + dr.toPlainString()
                                    + " vs CR " + cr.toPlainString() + ")", null, to, null, null,
                            new Evidence("difference 0", diff.toPlainString(), List.of(snap.ref()), det));
                }
            }
            return r.finish(null, refs);
        }
    }

    /** R13: each ledger adjustment entry's debit equals its credit. */
    public static final class AdjustmentBalanced implements Rule {
        public String id() { return "R13"; }
        public String name() { return "Ledger adjustment: debit = credit per entry"; }
        public String category() { return "ACCOUNTING_INTERNAL"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(ACC_LEDGER_ADJUSTMENTS));
            Optional<String> down = ctx.unavailable(ACC_LEDGER_ADJUSTMENTS);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            for (Rec rec : ctx.records(ACC_LEDGER_ADJUSTMENTS)) {
                JsonNode e = rec.payload();
                JsonNode lines = f.node(e, "adj.lines");
                BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
                boolean any = false;
                List<JsonNode> parts = new ArrayList<>();
                if (lines.isArray() && lines.size() > 0) lines.forEach(parts::add);
                else parts.add(e);
                for (JsonNode p : parts) {
                    Optional<BigDecimal> d = f.decimal(p, "adj.debit");
                    Optional<BigDecimal> c = f.decimal(p, "adj.credit");
                    any |= d.isPresent() || c.isPresent();
                    dr = dr.add(d.orElse(BigDecimal.ZERO));
                    cr = cr.add(c.orElse(BigDecimal.ZERO));
                }
                if (!any) {
                    r.add(rec.ref().recordKey(), Outcome.NOT_EVALUATED, "No debit/credit fields on entry", null, null,
                            null, null, Evidence.of(null, null, List.of(rec.ref())));
                    continue;
                }
                String client = Optional.ofNullable(f.text(e, "adj.client"))
                        .orElse(parts.isEmpty() ? null : f.text(parts.get(0), "adj.client"));
                String ref = Optional.ofNullable(f.text(e, "adj.ref"))
                        .orElse(parts.isEmpty() ? null : f.text(parts.get(0), "adj.ref"));
                if (dr.subtract(cr).abs().compareTo(ctx.config().tolerance()) <= 0) {
                    r.pass();
                } else {
                    r.add(rec.ref().recordKey(), Outcome.FAIL, "Unbalanced adjustment client " + client + ", ref " + ref
                                    + ": DR " + dr.toPlainString() + " vs CR " + cr.toPlainString(), null, null, null, null,
                            new Evidence("DR = CR", dr.toPlainString() + " / " + cr.toPlainString(), List.of(rec.ref()),
                                    Map.of("client", String.valueOf(client), "reference", String.valueOf(ref))));
                }
            }
            return r.finish(null, refs);
        }
    }

    /** R14: one OPEN period covers today; no gaps/overlaps; periods sit inside fiscal years. */
    public static final class PeriodHealth implements Rule {
        public String id() { return "R14"; }
        public String name() { return "Accounting period health"; }
        public String category() { return "ACCOUNTING_INTERNAL"; }

        record P(String name, String status, LocalDate start, LocalDate end, Rec rec) {
        }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(ACC_PERIODS), ctx.datasetRef(ACC_FISCAL_YEARS));
            Optional<String> down = ctx.unavailable(ACC_PERIODS);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            LocalDate today = LocalDate.now(ctx.clock().withZone(Fields.zone()));
            List<P> ps = new ArrayList<>();
            for (Rec rec : ctx.records(ACC_PERIODS)) {
                JsonNode p = rec.payload();
                LocalDate s = f.date(p, "period.start").orElse(null), e = f.date(p, "period.end").orElse(null);
                if (s == null || e == null) {
                    r.add(rec.ref().recordKey(), Outcome.FAIL, "Unparseable period dates: " + f.text(p, "period.start")
                            + " / " + f.text(p, "period.end"), null, null, null, null, Evidence.of("dates", "unparseable",
                            List.of(rec.ref())));
                    continue;
                }
                if (e.isBefore(s)) r.add(rec.ref().recordKey(), Outcome.FAIL, "Period ends before it starts", null, s,
                        null, null, Evidence.of("start <= end", s + " > " + e, List.of(rec.ref())));
                ps.add(new P(f.text(p, "period.name"), f.text(p, "period.status"), s, e, rec));
            }
            List<P> openToday = ps.stream().filter(p -> "OPEN".equalsIgnoreCase(p.status())
                    && !today.isBefore(p.start()) && !today.isAfter(p.end())).toList();
            List<P> covering = ps.stream().filter(p -> !today.isBefore(p.start()) && !today.isAfter(p.end())).toList();
            if (openToday.size() == 1) r.pass();
            else r.add("open-period-today", Outcome.FAIL, openToday.isEmpty()
                            ? "No OPEN period covers " + today + covering.stream().map(p -> " [" + p.name() + " is "
                            + p.status() + "]").reduce("", String::concat)
                            : openToday.size() + " OPEN periods cover " + today, null, today, null, null,
                    new Evidence("exactly 1 OPEN period covering " + today,
                            openToday.stream().map(P::name).toList().toString(),
                            (openToday.isEmpty() ? covering : openToday).stream().map(p -> p.rec().ref()).toList(),
                            Map.of()));
            List<P> sorted = ps.stream().sorted(Comparator.comparing(P::start)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                P a = sorted.get(i - 1), b = sorted.get(i);
                if (!b.start().isAfter(a.end())) {
                    r.add(a.name() + "/" + b.name(), Outcome.FAIL, "Periods overlap: " + a.name() + " ends " + a.end()
                            + ", " + b.name() + " starts " + b.start(), null, b.start(), null, null,
                            Evidence.of("no overlap", a.end() + " >= " + b.start(), List.of(a.rec().ref(), b.rec().ref())));
                } else if (b.start().isAfter(a.end().plusDays(1))) {
                    r.add(a.name() + "/" + b.name(), Outcome.FAIL, "Gap between " + a.name() + " (ends " + a.end()
                            + ") and " + b.name() + " (starts " + b.start() + ")", null, b.start(), null, null,
                            Evidence.of(a.end().plusDays(1).toString(), b.start().toString(),
                                    List.of(a.rec().ref(), b.rec().ref())));
                } else {
                    r.pass();
                }
            }
            if (ctx.unavailable(ACC_FISCAL_YEARS).isEmpty()) {
                List<LocalDate[]> fys = new ArrayList<>();
                List<Rec> fyRecs = ctx.records(ACC_FISCAL_YEARS);
                for (Rec fy : fyRecs) {
                    Optional<LocalDate> s = f.date(fy.payload(), "fy.start"), e = f.date(fy.payload(), "fy.end");
                    if (s.isPresent() && e.isPresent()) fys.add(new LocalDate[]{s.get(), e.get()});
                }
                for (P p : ps) {
                    Optional<LocalDate[]> fy = fys.stream().filter(y -> !p.start().isBefore(y[0])
                            && !p.end().isAfter(y[1])).findFirst();
                    if (fy.isEmpty()) {
                        r.add(p.name(), Outcome.FAIL, p.name() + " (" + p.start() + ".." + p.end()
                                + ") is not inside any fiscal year", null, p.start(), null, null,
                                Evidence.of("inside a fiscal year", "outside", List.of(p.rec().ref())));
                    } else if (p.start().getMonth() == fy.get()[0].getMonth()
                            && p.start().getYear() == fy.get()[0].getYear() && !p.start().equals(fy.get()[0])) {
                        r.add(p.name(), Outcome.FAIL, p.name() + " starts " + p.start()
                                + " but its fiscal year starts " + fy.get()[0], null, p.start(), null, null,
                                Evidence.of(fy.get()[0].toString(), p.start().toString(), List.of(p.rec().ref())));
                    } else {
                        r.pass();
                    }
                }
            }
            return r.finish(null, refs);
        }
    }

    /** R15: Accounting-Entry running balance recomputes from opening + debits - credits. */
    public static final class RunningBalance implements Rule {
        public String id() { return "R15"; }
        public String name() { return "Ledger statement running balance recomputes"; }
        public String category() { return "ACCOUNTING_INTERNAL"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(ACC_LEDGER_STATEMENT));
            Optional<String> down = ctx.unavailable(ACC_LEDGER_STATEMENT);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            for (Rec snap : ctx.records(ACC_LEDGER_STATEMENT)) {
                String ledger = snap.payload().path("query").path("entryType").asText();
                BigDecimal bal = null;
                int i = 0;
                boolean ok = true;
                for (JsonNode row : snap.payload().path("rows")) {
                    Optional<BigDecimal> stated = Fields.signedBalance(f.node(row, "stmt.balance"));
                    String desc = f.text(row, "stmt.desc");
                    if (bal == null || (desc != null && desc.toLowerCase().contains("opening"))) {
                        bal = stated.orElse(null);
                        i++;
                        continue;
                    }
                    BigDecimal dr = f.decimal(row, "stmt.debitAmount").orElse(BigDecimal.ZERO);
                    BigDecimal cr = f.decimal(row, "stmt.creditAmount").orElse(BigDecimal.ZERO);
                    BigDecimal expected = bal.add(dr).subtract(cr);
                    if (stated.isPresent() && expected.subtract(stated.get()).abs()
                            .compareTo(ctx.config().tolerance()) > 0) {
                        ok = false;
                        r.add(ledger + "#" + i, Outcome.FAIL, "Running balance " + stated.get().toPlainString()
                                        + " but recomputed " + expected.toPlainString(), null,
                                f.date(row, "stmt.date").orElse(null), null, ledger,
                                new Evidence(expected.toPlainString(), stated.get().toPlainString(), List.of(snap.ref()),
                                        Map.of("row", i, "description", String.valueOf(desc))));
                    }
                    bal = stated.orElse(expected);
                    i++;
                }
                if (ok && i > 0) r.pass();
            }
            return r.finish("Sign convention: DR positive, CR negative.", refs);
        }
    }
}
