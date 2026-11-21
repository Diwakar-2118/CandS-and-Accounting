package in.toucan.monitor.engine;

import in.toucan.monitor.engine.Model.Evidence;
import in.toucan.monitor.engine.Model.Outcome;
import in.toucan.monitor.engine.Model.RuleResult;
import in.toucan.monitor.engine.Model.SourceRef;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A validation rule. Implementations must never invent data: when inputs are missing they say so. */
public interface Rule {

    String id();

    String name();

    /** CS_INTERNAL, CS_TO_ACCOUNTING, ACCOUNTING_INTERNAL, RECON. */
    String category();

    List<RuleResult> evaluate(RuleContext ctx);

    /** Helper that accumulates results and produces the SUMMARY row. */
    final class Results {
        private final Rule rule;
        private final List<RuleResult> out = new ArrayList<>();
        private final Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        private int passedSilently;

        public Results(Rule rule) {
            this.rule = rule;
        }

        public void add(String subject, Outcome o, String msg, String processor, java.time.LocalDate date,
                        String tt, String ledger, Evidence ev) {
            out.add(new RuleResult(rule.id(), rule.name(), rule.category(), subject, o, msg, processor, date, tt,
                    ledger, ev));
            counts.merge(o, 1, Integer::sum);
        }

        public void fail(String subject, String msg, String processor, java.time.LocalDate date, Evidence ev) {
            add(subject, Outcome.FAIL, msg, processor, date, null, null, ev);
        }

        /** Count a pass without storing a row per record (keeps 500k-row runs small). */
        public void pass() {
            passedSilently++;
        }

        public List<RuleResult> finish(String note, List<SourceRef> sources) {
            int passed = passedSilently + counts.getOrDefault(Outcome.PASS, 0);
            int failed = counts.getOrDefault(Outcome.FAIL, 0) + counts.getOrDefault(Outcome.MISSING, 0);
            int evaluated = passed + failed + counts.getOrDefault(Outcome.WARN, 0)
                    + counts.getOrDefault(Outcome.PENDING, 0);
            Outcome summary = failed > 0 ? Outcome.FAIL
                    : evaluated == 0 ? Outcome.NOT_EVALUATED
                    : counts.getOrDefault(Outcome.WARN, 0) > 0 ? Outcome.WARN : Outcome.PASS;
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("evaluated", evaluated);
            details.put("passed", passed);
            details.put("failed", failed);
            counts.forEach((k, v) -> details.put(k.name().toLowerCase(), v));
            details.put("passed_unlisted", passedSilently);
            String msg = (note == null ? "" : note + " ") + "evaluated=" + evaluated + ", passed=" + passed
                    + ", failed=" + failed;
            out.add(new RuleResult(rule.id(), rule.name(), rule.category(), "SUMMARY", summary, msg.trim(), null,
                    null, null, null, new Evidence(null, null, sources, details)));
            return out;
        }

        /** Whole rule cannot run (source down, config missing). */
        public List<RuleResult> notEvaluated(String why, List<SourceRef> sources) {
            out.clear();
            out.add(new RuleResult(rule.id(), rule.name(), rule.category(), "SUMMARY", Outcome.NOT_EVALUATED, why,
                    null, null, null, null, new Evidence(null, null, sources, Map.of())));
            return out;
        }
    }
}
