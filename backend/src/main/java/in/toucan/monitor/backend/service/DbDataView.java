package in.toucan.monitor.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.toucan.monitor.backend.domain.CsTxnEntity;
import in.toucan.monitor.backend.domain.DatasetSnapshotEntity;
import in.toucan.monitor.backend.domain.SourceRecordEntity;
import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.collector.DatasetSpec;
import in.toucan.monitor.collector.Datasets;
import in.toucan.monitor.engine.DataView;
import in.toucan.monitor.engine.Model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Engine view over the database for one window. */
public class DbDataView implements DataView {

    /** Snapshot datasets where only the latest successful run's queries are current. */
    static final Set<String> LATEST_RUN_ONLY = Set.of(Datasets.ACC_TRIAL_BALANCE, Datasets.ACC_LEDGER_STATEMENT,
            Datasets.ACC_MERCHANT_LEDGER);

    private final Repos.SnapshotRepo snapshots;
    private final Repos.SourceRecordRepo records;
    private final Repos.TxnRepo txns;
    private final ObjectMapper om;
    private final LocalDate from;
    private final LocalDate to;
    private final List<Model.TtMapping> mappings;
    private final PayuReconLoader payu;
    private final Map<String, List<Model.Rec>> cache = new HashMap<>();
    private final Map<String, Model.DatasetState> states = new HashMap<>();

    public DbDataView(Repos.SnapshotRepo snapshots, Repos.SourceRecordRepo records, Repos.TxnRepo txns,
                      ObjectMapper om, LocalDate from, LocalDate to, List<Model.TtMapping> mappings,
                      PayuReconLoader payu) {
        this.snapshots = snapshots;
        this.records = records;
        this.txns = txns;
        this.om = om;
        this.from = from;
        this.to = to;
        this.mappings = mappings;
        this.payu = payu;
    }

    @Override
    public Model.DatasetState state(String dataset) {
        return states.computeIfAbsent(dataset, ds -> datasetState(snapshots, ds));
    }

    public static Model.DatasetState datasetState(Repos.SnapshotRepo snapshots, String ds) {
        Optional<DatasetSnapshotEntity> latest = snapshots.findFirstByDatasetOrderByIdDesc(ds);
        if (latest.isEmpty()) return Model.DatasetState.unavailable("never collected");
        List<DatasetSnapshotEntity> sameRun = snapshots.findByDatasetAndRunId(ds, latest.get().runId);
        boolean anyOk = sameRun.stream().anyMatch(s -> s.ok);
        String err = sameRun.stream().filter(s -> !s.ok).map(s -> s.error).findFirst().orElse(null);
        boolean truncated = sameRun.stream().anyMatch(s -> s.truncated);
        Instant lastOk = snapshots.findFirstByDatasetAndOkTrueOrderByIdDesc(ds).map(s -> s.retrievedAt).orElse(null);
        return new Model.DatasetState(anyOk, err, lastOk, truncated);
    }

    @Override
    public List<Model.Rec> records(String dataset) {
        return cache.computeIfAbsent(dataset, this::load);
    }

    private List<Model.Rec> load(String ds) {
        List<SourceRecordEntity> rows;
        if (Datasets.CS_TRANSACTIONS.equals(ds)) {
            List<Long> ids = txns.findBySalesDateBetween(from, to).stream().map(t -> t.sourceRecordId).toList();
            rows = new ArrayList<>();
            for (int i = 0; i < ids.size(); i += 1000) {
                rows.addAll(records.findAllById(ids.subList(i, Math.min(ids.size(), i + 1000))));
            }
        } else if (LATEST_RUN_ONLY.contains(ds)) {
            Long run = snapshots.findFirstByDatasetAndOkTrueOrderByIdDesc(ds).map(s -> s.runId).orElse(null);
            rows = run == null ? List.of() : records.findByDatasetAndLastRunId(ds, run);
        } else {
            rows = records.findByDataset(ds);
        }
        List<Model.Rec> out = new ArrayList<>(rows.size());
        for (SourceRecordEntity r : rows) out.add(new Model.Rec(ref(r), read(r.payload)));
        return out;
    }

    public static Model.SourceRef ref(SourceRecordEntity r) {
        Optional<DatasetSpec> spec = Datasets.find(r.dataset);
        return new Model.SourceRef(r.systemName, r.dataset, r.endpoint, spec.map(DatasetSpec::screen).orElse(null),
                r.naturalKey, r.requestParams, r.retrievedAt);
    }

    private com.fasterxml.jackson.databind.JsonNode read(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    @Override
    public List<Model.TtMapping> mappings() {
        return mappings;
    }

    @Override
    public List<Model.ReconTotals> payuRecon() {
        return payu.load();
    }

    @Override
    public boolean payuReconConfigured() {
        return payu.configured();
    }

    public Map<String, CsTxnEntity> windowTxnsByKey() {
        Map<String, CsTxnEntity> m = new HashMap<>();
        txns.findBySalesDateBetween(from, to).forEach(t -> m.put(t.naturalKey, t));
        return m;
    }
}
