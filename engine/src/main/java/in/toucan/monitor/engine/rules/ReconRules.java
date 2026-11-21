package in.toucan.monitor.engine.rules;

import com.fasterxml.jackson.databind.JsonNode;
import in.toucan.monitor.engine.Fields;
import in.toucan.monitor.engine.Model.Evidence;
import in.toucan.monitor.engine.Model.Outcome;
import in.toucan.monitor.engine.Model.Rec;
import in.toucan.monitor.engine.Model.ReconTotals;
import in.toucan.monitor.engine.Model.RuleResult;
import in.toucan.monitor.engine.Rule;
import in.toucan.monitor.engine.RuleContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static in.toucan.monitor.collector.Datasets.CS_TRANSACTIONS;

/** Rule 16: PayU recon file vs C&S. */
public final class ReconRules {

    private ReconRules() {
    }

    /**
     * R16: per business date, PayU recon totals (count, transaction amount, net amount) vs C&S
     * transactions with platformType PAYU. Fee/tax/settlement totals are carried in evidence; comparing
     * them to PU TT postings needs the PU mappings confirmed and is reported by R09.
     */
    public static final class PayuRecon implements Rule {
        public String id() { return "R16"; }
        public String name() { return "PayU recon file vs C&S"; }
        public String category() { return "RECON"; }

        public List<RuleResult> evaluate(RuleContext ctx) {
            Results r = new Results(this);
            Fields f = ctx.f();
            var refs = List.of(ctx.datasetRef(CS_TRANSACTIONS));
            if (!ctx.data().payuReconConfigured()) {
                return r.notEvaluated("PayU recon file source not confirmed (Recon app, Payment Files or SFTP). "
                        + "Configure monitor.payu-recon once the business confirms it.", refs);
            }
            Optional<String> down = ctx.unavailable(CS_TRANSACTIONS);
            if (down.isPresent()) return r.notEvaluated(down.get(), refs);
            Map<LocalDate, long[]> cnt = new HashMap<>();
            Map<LocalDate, BigDecimal[]> amt = new HashMap<>();
            for (Rec rec : ctx.records(CS_TRANSACTIONS)) {
                JsonNode t = rec.payload();
                String p = f.text(t, "txn.platform");
                if (p == null || !ctx.config().payuPlatformValues().contains(p.toUpperCase())) continue;
                LocalDate d = f.date(t, "txn.salesDateTime").orElse(null);
                if (d == null) continue;
                cnt.computeIfAbsent(d, k -> new long[1])[0]++;
                BigDecimal[] a = amt.computeIfAbsent(d, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                a[0] = a[0].add(f.decimal(t, "txn.amount").orElse(BigDecimal.ZERO));
                a[1] = a[1].add(f.decimal(t, "txn.netAmount").orElse(BigDecimal.ZERO));
            }
            for (ReconTotals rt : ctx.data().payuRecon()) {
                if (!ctx.inWindow(rt.date())) continue;
                long csCount = cnt.getOrDefault(rt.date(), new long[1])[0];
                BigDecimal[] a = amt.getOrDefault(rt.date(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                Map<String, Object> det = new LinkedHashMap<>();
                det.put("recon.count", rt.count());
                det.put("cs.count", csCount);
                det.put("recon.totalAmount", String.valueOf(rt.totalAmount()));
                det.put("cs.txnAmount", a[0].toPlainString());
                det.put("recon.netAmount", String.valueOf(rt.netAmount()));
                det.put("cs.netAmount", a[1].toPlainString());
                det.put("recon.processingFees", String.valueOf(rt.processingFees()));
                det.put("recon.serviceTax", String.valueOf(rt.serviceTax()));
                det.put("recon.settlementAmount", String.valueOf(rt.settlementAmount()));
                BigDecimal tol = ctx.config().tolerance();
                boolean ok = rt.count() == csCount
                        && (rt.totalAmount() == null || rt.totalAmount().subtract(a[0]).abs().compareTo(tol) <= 0)
                        && (rt.netAmount() == null || rt.netAmount().subtract(a[1]).abs().compareTo(tol) <= 0);
                Evidence ev = new Evidence("recon totals", "C&S totals", List.of(rt.source(), ctx.datasetRef(CS_TRANSACTIONS)), det);
                if (ok) r.add("PAYU " + rt.date(), Outcome.PASS, "PayU totals match C&S", "PAYU", rt.date(), null, null, ev);
                else r.add("PAYU " + rt.date(), Outcome.FAIL, "PayU recon differs from C&S for " + rt.date(), "PAYU",
                        rt.date(), null, null, ev);
            }
            return r.finish(null, refs);
        }
    }
}
