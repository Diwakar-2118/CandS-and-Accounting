package in.toucan.monitor.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.toucan.monitor.backend.domain.*;
import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.collector.CollectedRecord;
import in.toucan.monitor.collector.CollectionResult;
import in.toucan.monitor.collector.Datasets;
import in.toucan.monitor.engine.EngineConfig;
import in.toucan.monitor.engine.Fields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Idempotent storage: natural key per dataset + content hash. Unchanged records only get
 * last_seen_at/last_run_id bumped; changed ones are updated and the old version goes to history.
 */
@Service
public class IngestionService {

    private final Repos.SnapshotRepo snapshots;
    private final Repos.SourceRecordRepo records;
    private final Repos.HistoryRepo history;
    private final Repos.TxnRepo txns;
    private final Repos.ProcessorRepo processors;
    private final ObjectMapper om;
    private final EngineConfig engineConfig;

    public IngestionService(Repos.SnapshotRepo snapshots, Repos.SourceRecordRepo records, Repos.HistoryRepo history,
                            Repos.TxnRepo txns, Repos.ProcessorRepo processors, ObjectMapper om,
                            in.toucan.monitor.backend.config.MonitorProperties props) {
        this.snapshots = snapshots;
        this.records = records;
        this.history = history;
        this.txns = txns;
        this.processors = processors;
        this.om = om;
        this.engineConfig = props.toEngineConfig();
    }

    @Transactional
    public int store(CollectionResult r, Long runId) {
        DatasetSnapshotEntity s = new DatasetSnapshotEntity();
        s.runId = runId;
        s.dataset = r.dataset();
        s.systemName = Datasets.find(r.dataset()).map(d -> d.system().name()).orElse("EXTERNAL");
        s.endpoint = r.endpoint();
        s.requestParams = r.requestParams();
        s.ok = r.ok();
        s.error = r.error();
        s.recordCount = r.records().size();
        s.totalReported = r.totalReported();
        s.truncated = r.truncated();
        s.retrievedAt = r.retrievedAt();
        snapshots.save(s);
        if (!r.ok() || r.records().isEmpty()) return 0;

        Map<String, SourceRecordEntity> existing = new HashMap<>();
        List<String> keys = r.records().stream().map(CollectedRecord::naturalKey).distinct().toList();
        for (int i = 0; i < keys.size(); i += 500) {
            records.findByDatasetAndKeys(r.dataset(), keys.subList(i, Math.min(keys.size(), i + 500)))
                    .forEach(e -> existing.put(e.naturalKey, e));
        }
        int n = 0;
        for (CollectedRecord c : r.records()) {
            SourceRecordEntity e = existing.get(c.naturalKey());
            String payload = write(Masker.mask(c.payload()));
            if (e == null) {
                e = new SourceRecordEntity();
                e.dataset = c.dataset();
                e.systemName = c.system().name();
                e.naturalKey = c.naturalKey();
                e.firstSeenAt = c.retrievedAt();
            } else if (!e.contentHash.equals(c.contentHash())) {
                SourceRecordHistoryEntity h = new SourceRecordHistoryEntity();
                h.sourceRecordId = e.id;
                h.contentHash = e.contentHash;
                h.payload = e.payload;
                h.retrievedAt = e.retrievedAt;
                h.runId = e.lastRunId;
                history.save(h);
            }
            e.endpoint = c.endpoint();
            e.requestParams = c.requestParams();
            e.contentHash = c.contentHash();
            e.payload = payload;
            e.retrievedAt = c.retrievedAt();
            e.lastSeenAt = c.retrievedAt();
            e.lastRunId = runId;
            e = records.save(e);
            existing.put(c.naturalKey(), e);
            if (Datasets.CS_TRANSACTIONS.equals(c.dataset())) project(e, c.payload());
            n++;
        }
        return n;
    }

    private void project(SourceRecordEntity rec, JsonNode p) {
        Fields f = engineConfig.f();
        CsTxnEntity t = txns.findById(rec.id).orElseGet(CsTxnEntity::new);
        t.sourceRecordId = rec.id;
        t.naturalKey = rec.naturalKey;
        t.rrn = f.text(p, "txn.rrn");
        t.arn = f.text(p, "txn.arn");
        t.invoiceNumber = f.text(p, "txn.invoice");
        t.merchantNumber = f.text(p, "txn.mid");
        t.merchantName = trim(f.text(p, "txn.merchantName"), 300);
        t.terminalNumber = f.text(p, "txn.terminal");
        t.runNumber = f.text(p, "txn.runNumber");
        t.schemeCode = f.text(p, "txn.scheme");
        t.platformType = f.text(p, "txn.platform");
        t.transactionCode = f.text(p, "txn.transactionCode");
        t.settlementStatus = f.text(p, "txn.settlementStatus");
        t.lifeCycleStatus = f.text(p, "txn.lifeCycleStatus");
        t.transactionStatus = f.text(p, "txn.transactionStatus");
        t.txnStatus = f.text(p, "txn.txnStatus");
        t.paymentReleaseStatus = f.text(p, "txn.paymentReleaseStatus");
        t.dbOrCr = f.text(p, "txn.dbOrCr");
        t.txnAmount = f.decimal(p, "txn.amount").map(x -> x.setScale(4, RoundingMode.HALF_UP)).orElse(null);
        t.netAmount = f.decimal(p, "txn.netAmount").map(x -> x.setScale(4, RoundingMode.HALF_UP)).orElse(null);
        t.salesDateTime = trim(f.text(p, "txn.salesDateTime"), 40);
        t.salesDate = f.date(p, "txn.salesDateTime").orElse(null);
        if (t.accountingStatus == null) t.accountingStatus = "NOT_CHECKED";
        txns.save(t);
        if (t.platformType != null && !processors.existsById(t.platformType)) {
            ProcessorEntity pe = new ProcessorEntity();
            pe.code = t.platformType;
            pe.displayName = t.platformType;
            pe.active = true;
            pe.firstSeenAt = Instant.now();
            processors.save(pe);
        }
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private String write(JsonNode n) {
        try {
            return om.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> Map<String, T> index(List<T> l, Function<T, String> k) {
        return l.stream().collect(Collectors.toMap(k, x -> x, (a, b) -> b));
    }
}
