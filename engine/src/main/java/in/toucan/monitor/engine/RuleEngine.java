package in.toucan.monitor.engine;

import in.toucan.monitor.engine.Model.Evidence;
import in.toucan.monitor.engine.Model.Outcome;
import in.toucan.monitor.engine.Model.RuleResult;
import in.toucan.monitor.engine.rules.AccountingInternalRules;
import in.toucan.monitor.engine.rules.CsInternalRules;
import in.toucan.monitor.engine.rules.CsToAccountingRules;
import in.toucan.monitor.engine.rules.ReconRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs the rule set. A crashing rule yields a NOT_EVALUATED summary, never a silent pass. */
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RuleEngine standard() {
        return new RuleEngine(List.of(
                new CsInternalRules.FeeArithmetic(),
                new CsInternalRules.PaymentEqualsSumOfTxns(),
                new CsInternalRules.ReleasedWithoutPayment(),
                new CsInternalRules.JvNotTriggered(),
                new CsInternalRules.FileHealth(),
                new CsInternalRules.UpiBdfGenerated(),
                new CsInternalRules.DecryptionFailures(),
                new CsToAccountingRules.PostedAndBalanced(),
                new CsToAccountingRules.PostingMatchesTtMapping(),
                new CsToAccountingRules.PendingVsMissing(),
                new CsToAccountingRules.DuplicatePostings(),
                new AccountingInternalRules.TrialBalanceBalanced(),
                new AccountingInternalRules.AdjustmentBalanced(),
                new AccountingInternalRules.PeriodHealth(),
                new AccountingInternalRules.RunningBalance(),
                new ReconRules.PayuRecon()));
    }

    public List<Rule> rules() {
        return rules;
    }

    public List<RuleResult> run(RuleContext ctx, Set<String> onlyRuleIds) {
        List<RuleResult> all = new ArrayList<>();
        for (Rule r : rules) {
            if (onlyRuleIds != null && !onlyRuleIds.isEmpty() && !onlyRuleIds.contains(r.id())) continue;
            try {
                all.addAll(r.evaluate(ctx));
            } catch (RuntimeException e) {
                log.error("Rule {} crashed", r.id(), e);
                all.add(new RuleResult(r.id(), r.name(), r.category(), "SUMMARY", Outcome.NOT_EVALUATED,
                        "Rule error: " + e, null, null, null, null,
                        new Evidence(null, null, List.of(), Map.of("exception", e.toString()))));
            }
        }
        return all;
    }
}
