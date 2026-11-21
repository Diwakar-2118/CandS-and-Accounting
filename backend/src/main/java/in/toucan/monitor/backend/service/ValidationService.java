package in.toucan.monitor.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.toucan.monitor.backend.config.MonitorProperties;
import in.toucan.monitor.backend.domain.CsTxnEntity;
import in.toucan.monitor.backend.domain.RuleResultEntity;
import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.engine.Model;
import in.toucan.monitor.engine.RuleContext;
import in.toucan.monitor.engine.RuleEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs rules 1-16 over stored data and persists results with evidence. */
@Service
public class ValidationService {

    private final Repos.SnapshotRepo snapshots;
    private final Repos.SourceRecordRepo records;
    private final Repos.TxnRepo txns;
    private final Repos.ResultRepo results;
    private final MappingService mappings;
    private final PayuReconLoader payu;
    private final MonitorProperties props;
    private final ObjectMapper om;
    private final Clock clock;
    private final RuleEngine engine = RuleEngine.standard();

    public ValidationService(Repos.SnapshotRepo snapshots, Repos.SourceRecordRepo records, Repos.TxnRepo txns,
                             Repos.ResultRepo results, MappingService mappings, PayuReconLoader payu,
                             MonitorProperties props, ObjectMapper om, Clock clock) {
        this.snapshots = snapshots;
        this.records = records;
        this.txns = txns;
        this.results = results;
        this.mappings = mappings;
        this.payu = payu;
        this.props = props;
        this.om = om;
        this.clock = clock;
    }

    public record Summary(int passed, int failed, int notEvaluated) {
    }

    @Transactional
    public Summary validate(Long runId, LocalDate from, LocalDate to) {
        DbDataView view = new DbDataView(snapshots, records, txns, om, from, to, mappings.engineMappings(), payu);
        RuleContext ctx = new RuleContext(view, props.toEngineConfig(), clock, from, to);
        List<Model.RuleResult> rs = engine.run(ctx, null);

        Instant now = clock.instant();
        List<RuleResultEntity> rows = new ArrayList<>(rs.size());
        int passed = 0, failed = 0, ne = 0;
        Map<String, CsTxnEntity> window = view.windowTxnsByKey();
        List<CsTxnEntity> touched = new ArrayList<>();
        for (Model.RuleResult r : rs) {
            RuleResultEntity e = new RuleResultEntity();
            e.runId = runId;
            e.ruleId = r.ruleId();
            e.ruleName = r.ruleName();
            e.category = r.category();
            e.subjectKey = cut(r.subjectKey(), 2000);
            e.outcome = r.outcome().name();
            e.message = r.message();
            e.processor = r.processor();
            e.businessDate = r.businessDate();
            e.ttCode = r.ttCode();
            e.ledger = cut(r.ledger(), 300);
            e.evidence = json(r.evidence());
            e.createdAt = now;
            rows.add(e);
            if ("SUMMARY".equals(r.subjectKey())) {
                Object p = r.evidence().details().get("passed");
                Object f = r.evidence().details().get("failed");
                passed += p instanceof Integer i ? i : 0;
                failed += f instanceof Integer i ? i : 0;
                if (r.outcome() == Model.Outcome.NOT_EVALUATED) ne++;
            }
            // Accounting status per transaction from R08 (posted) and R10 (not posted).
            if (("R08".equals(r.ruleId()) || "R10".equals(r.ruleId())) && window.containsKey(r.subjectKey())) {
                CsTxnEntity t = window.get(r.subjectKey());
                t.accountingStatus = switch (r.outcome()) {
                    case PASS -> "COMPLETED";
                    case FAIL -> "FAILED";
                    case PENDING -> "PENDING";
                    case MISSING -> "MISSING";
                    default -> t.accountingStatus;
                };
                touched.add(t);
            }
        }
        results.saveAll(rows);
        txns.saveAll(touched);
        return new Summary(passed, failed, ne);
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
