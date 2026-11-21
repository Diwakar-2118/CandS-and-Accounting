package in.toucan.monitor.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.toucan.monitor.backend.config.MonitorProperties;
import in.toucan.monitor.backend.domain.*;
import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.collector.Datasets;
import in.toucan.monitor.engine.Fields;
import in.toucan.monitor.engine.Model;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static in.toucan.monitor.collector.Datasets.*;

/** Read models for every dashboard screen and export. Rows are flat maps carrying source references. */
@Service
public class QueryService {

    public static final Map<String, List<String>> FILE_CATEGORIES = new LinkedHashMap<>();

    static {
        FILE_CATEGORIES.put("scheme", List.of(CS_SCHEME_FILES));
        FILE_CATEGORIES.put("payment", List.of(CS_PAYMENT_FILES));
        FILE_CATEGORIES.put("reports", List.of(CS_REPORTS));
        FILE_CATEGORIES.put("processed", List.of(CS_PROCESSED_FILES));
        FILE_CATEGORIES.put("jv", List.of(CS_JV));
        FILE_CATEGORIES.put("upi-bdf", List.of(CS_UPI_SUMMARY_BDF));
        FILE_CATEGORIES.put("bulk", List.of(CS_BULK_DOWNLOAD));
        FILE_CATEGORIES.put("payment-exceptions", List.of(CS_PAYMENT_EXCEPTIONS));
        FILE_CATEGORIES.put("rejects", List.of(CS_SCHEME_REJECTS));
        FILE_CATEGORIES.put("rolling-reserve", List.of(CS_ROLLING_RESERVE));
        FILE_CATEGORIES.put("instant-settlement", List.of(CS_INSTANT_EXCEPTIONS));
        FILE_CATEGORIES.put("payments", List.of(CS_PAYMENTS_CREDIT, CS_PAYMENTS_DEBIT));
    }

    /** UI label vs API field caution observed in Global Search. */
    public static final List<Map<String, String>> LABEL_MISMATCHES = List.of(
            Map.of("uiLabel", "Scheme Status", "apiField", "settlementStatus", "screen", "#/cands/global-search"),
            Map.of("uiLabel", "Settlement Status", "apiField", "transactionStatus", "screen", "#/cands/global-search"));

    private final Repos.TxnRepo txns;
    private final Repos.SourceRecordRepo records;
    private final Repos.SnapshotRepo snapshots;
    private final Repos.ResultRepo results;
    private final Repos.RunRepo runs;
    private final Repos.ApiCallRepo calls;
    private final Repos.MappingRepo mappings;
    private final Repos.ProcessorRepo processors;
    private final Repos.HistoryRepo history;
    private final ObjectMapper om;
    private final Fields f;

    public QueryService(Repos.TxnRepo txns, Repos.SourceRecordRepo records, Repos.SnapshotRepo snapshots,
                        Repos.ResultRepo results, Repos.RunRepo runs, Repos.ApiCallRepo calls,
                        Repos.MappingRepo mappings, Repos.ProcessorRepo processors, Repos.HistoryRepo history,
                        ObjectMapper om, MonitorProperties props) {
        this.txns = txns;
        this.records = records;
        this.snapshots = snapshots;
        this.results = results;
        this.runs = runs;
        this.calls = calls;
        this.mappings = mappings;
        this.processors = processors;
        this.history = history;
        this.om = om;
        this.f = props.toEngineConfig().f();
    }

    // ---------------------------------------------------------------- helpers

    JsonNode read(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    Object tree(String s) {
        try {
            return om.readValue(s, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    public Model.DatasetState state(String ds) {
        return DbDataView.datasetState(snapshots, ds);
    }

    public Map<String, Object> stateMap(String ds) {
        Model.DatasetState s = state(ds);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataset", ds);
        m.put("label", Datasets.find(ds).map(x -> x.label()).orElse(ds));
        m.put("available", s.available());
        m.put("error", s.error());
        m.put("lastSuccessAt", s.lastSuccessAt());
        m.put("truncated", s.truncated());
        m.put("endpoint", Datasets.find(ds).map(x -> x.path()).orElse(null));
        m.put("screen", Datasets.find(ds).map(x -> x.screen()).orElse(null));
        return m;
    }

    private static Map<String, Object> source(SourceRecordEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_system", r.systemName);
        m.put("_dataset", r.dataset);
        m.put("_endpoint", r.endpoint);
        m.put("_screen", Datasets.find(r.dataset).map(x -> x.screen()).orElse(null));
        m.put("_recordKey", r.naturalKey);
        m.put("_requestParams", r.requestParams);
        m.put("_retrievedAt", r.retrievedAt);
        m.put("_recordId", r.id);
        return m;
    }

    /** Latest results per rule (each rule's most recent run). */
    public List<RuleResultEntity> latestResults(String ruleId) {
        List<RuleResultEntity> out = new ArrayList<>();
        Collection<String> ids = ruleId == null ? in.toucan.monitor.engine.RuleEngine.standard().rules().stream()
                .map(in.toucan.monitor.engine.Rule::id).toList() : List.of(ruleId);
        for (String id : ids) {
            Long run = results.latestRunForRule(id);
            if (run != null) out.addAll(results.findByRunIdAndRuleId(run, id));
        }
        return out;
    }

    public Map<String, Object> resultRow(RuleResultEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", r.ruleId);
        m.put("ruleName", r.ruleName);
        m.put("category", r.category);
        m.put("subjectKey", r.subjectKey);
        m.put("outcome", r.outcome);
        m.put("message", r.message);
        m.put("processor", r.processor);
        m.put("businessDate", r.businessDate);
        m.put("ttCode", r.ttCode);
        m.put("ledger", r.ledger);
        m.put("runId", r.runId);
        m.put("evaluatedAt", r.createdAt);
        m.put("evidence", tree(r.evidence));
        return m;
    }

    // ---------------------------------------------------------------- transactions

    public Map<String, Object> transactionRow(CsTxnEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.naturalKey);
        m.put("recordId", t.sourceRecordId);
        m.put("salesDateTime", t.salesDateTime);
        m.put("rrn", t.rrn);
        m.put("arn", t.arn);
        m.put("invoiceNumber", t.invoiceNumber);
        m.put("merchantNumber", t.merchantNumber);
        m.put("merchantName", t.merchantName);
        m.put("terminalNumber", t.terminalNumber);
        m.put("runNumber", t.runNumber);
        m.put("schemeCode", t.schemeCode);
        m.put("platformType", t.platformType);
        m.put("transactionCode", t.transactionCode);
        m.put("txnAmount", t.txnAmount);
        m.put("netAmount", t.netAmount);
        m.put("settlementStatus", t.settlementStatus);
        m.put("lifeCycleStatus", t.lifeCycleStatus);
        m.put("transactionStatus", t.transactionStatus);
        m.put("txnStatus", t.txnStatus);
        m.put("paymentReleaseStatusEnum", t.paymentReleaseStatus);
        m.put("dbOrcrToMerchant", t.dbOrCr);
        m.put("accountingStatus", t.accountingStatus);
        m.put("_system", "CANDS");
        m.put("_endpoint", "/api/cands/transaction/search");
        m.put("_screen", "#/cands/global-search");
        return m;
    }

    public Page<Map<String, Object>> transactions(Filters flt, int page, int size) {
        return txns.findAll(flt.spec(), PageRequest.of(page, Math.min(size, 1000),
                Sort.by(Sort.Direction.DESC, "salesDate", "sourceRecordId"))).map(this::transactionRow);
    }

    public List<CsTxnEntity> transactionsAll(Filters flt) {
        return txns.findAll(flt.spec());
    }

    public Map<String, Object> lifecycle(Long recordId) {
        CsTxnEntity t = txns.findById(recordId).orElseThrow(() -> new NoSuchElementException("No transaction " + recordId));
        SourceRecordEntity rec = records.findById(recordId).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transaction", transactionRow(t));
        out.put("raw", tree(rec.payload));
        out.put("source", source(rec));
        out.put("history", history.findBySourceRecordIdOrderByIdDesc(recordId).stream().map(h -> Map.of(
                "retrievedAt", h.retrievedAt, "contentHash", h.contentHash, "runId", String.valueOf(h.runId))).toList());
        // Payment: run + MID (+ side)
        List<Map<String, Object>> payments = new ArrayList<>();
        for (String ds : List.of(CS_PAYMENTS_CREDIT, CS_PAYMENTS_DEBIT)) {
            for (SourceRecordEntity p : records.findByDataset(ds)) {
                JsonNode n = read(p.payload);
                if (Objects.equals(f.text(n, "payment.runNumber"), t.runNumber)
                        && Objects.equals(f.text(n, "payment.mid"), t.merchantNumber)) {
                    Map<String, Object> m = source(p);
                    m.put("payload", tree(p.payload));
                    payments.add(m);
                }
            }
        }
        out.put("payments", payments);
        out.put("paymentTransactions", records.findByDataset(CS_PAYMENT_TXNS).stream().filter(s -> {
            JsonNode q = read(s.payload).path("query");
            return Objects.equals(q.path("runNumber").asText(null), t.runNumber)
                    && Objects.equals(q.path("merchantNumber").asText(null), t.merchantNumber);
        }).map(s -> {
            Map<String, Object> m = source(s);
            m.put("payload", tree(s.payload));
            return m;
        }).toList());
        // Scheme rejects by ARN / RRN
        out.put("schemeRejects", records.findByDataset(CS_SCHEME_REJECTS).stream().filter(r -> {
            JsonNode n = read(r.payload);
            return (t.arn != null && t.arn.equals(n.path("arn").asText(null)))
                    || (t.rrn != null && t.rrn.equals(n.path("rrn").asText(null)));
        }).map(r -> {
            Map<String, Object> m = source(r);
            m.put("payload", tree(r.payload));
            return m;
        }).toList());
        // Accounting postings
        Optional<SourceRecordEntity> post = t.rrn == null ? Optional.empty() : records.findByDatasetAndNaturalKey(
                ACC_RRN_POSTINGS, "query:{\"rrn\":\"" + t.rrn + "\"}");
        out.put("accountingPostings", post.map(p -> {
            Map<String, Object> m = source(p);
            m.put("payload", tree(p.payload));
            return m;
        }).orElse(null));
        // Rule results for this transaction
        List<Map<String, Object>> rr = new ArrayList<>();
        for (RuleResultEntity r : latestResults(null)) {
            if (r.subjectKey.equals(t.naturalKey) || (t.rrn != null && (r.subjectKey.startsWith(t.rrn + "#")
                    || r.subjectKey.startsWith(t.rrn + "|")))) {
                rr.add(resultRow(r));
            }
        }
        out.put("ruleResults", rr);
        return out;
    }

    // ---------------------------------------------------------------- files

    public List<Map<String, Object>> files(String category, Filters flt, String status) {
        List<String> datasets = FILE_CATEGORIES.getOrDefault(category, List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ds : datasets) {
            for (SourceRecordEntity r : records.findByDataset(ds)) {
                JsonNode n = read(r.payload);
                LocalDate d = firstDate(n);
                if (d != null && flt.from() != null && (d.isBefore(flt.from()) || d.isAfter(flt.to()))) continue;
                String st = firstText(n, "file.status", "jv.status", "bdf.generated");
                if (status != null && !status.isBlank() && !status.equalsIgnoreCase(st)) continue;
                String flag = f.text(n, "file.flag");
                if (Filters.has(flt.fileType()) && flag != null && !flt.fileType().equalsIgnoreCase(flag)
                        && !String.valueOf(f.text(n, "file.name")).toUpperCase().contains(flt.fileType().toUpperCase())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", d);
                m.put("name", firstText(n, "file.name", "jv.name", "bdf.file"));
                m.put("rawStatus", st);
                m.put("dashboardStatus", DashboardStatus.of(ds, st));
                m.put("flag", flag);
                m.put("payload", tree(r.payload));
                m.putAll(source(r));
                out.add(m);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("date"))).reversed());
        return out;
    }

    LocalDate firstDate(JsonNode n) {
        for (String k : List.of("file.date", "jv.date", "bdf.date", "txn.salesDateTime")) {
            Optional<LocalDate> d = f.date(n, k);
            if (d.isPresent()) return d.get();
        }
        return null;
    }

    String firstText(JsonNode n, String... keys) {
        for (String k : keys) {
            String v = f.text(n, k);
            if (v != null) return v;
        }
        return null;
    }

    // ---------------------------------------------------------------- validation

    public List<Map<String, Object>> validation(String ruleId, String outcome, String category, Filters flt,
                                                boolean includeSummary) {
        return latestResults(Filters.has(ruleId) ? ruleId : null).stream()
                .filter(r -> includeSummary || !"SUMMARY".equals(r.subjectKey))
                .filter(r -> !Filters.has(outcome) || outcome.equalsIgnoreCase(r.outcome))
                .filter(r -> !Filters.has(category) || category.equalsIgnoreCase(r.category))
                .filter(r -> !Filters.has(flt.processor()) || flt.processor().equalsIgnoreCase(r.processor))
                .filter(r -> !Filters.has(flt.ttCode()) || flt.ttCode().equalsIgnoreCase(r.ttCode))
                .filter(r -> r.businessDate == null || flt.from() == null
                        || (!r.businessDate.isBefore(flt.from()) && !r.businessDate.isAfter(flt.to())))
                .map(this::resultRow).toList();
    }

    /** TT/sub-module x ledger x date matrix from per-RRN postings, against C&S transaction counts. */
    public List<Map<String, Object>> matrix(Filters flt) {
        Map<String, CsTxnEntity> byRrn = new HashMap<>();
        Map<LocalDate, Long> csCount = new TreeMap<>();
        for (CsTxnEntity t : transactionsAll(flt)) {
            if (t.rrn != null) byRrn.put(t.rrn, t);
            if (t.salesDate != null) csCount.merge(t.salesDate, 1L, Long::sum);
        }
        Map<List<Object>, BigDecimal[]> agg = new TreeMap<>(Comparator.comparing(Object::toString));
        Map<List<Object>, Long> lines = new HashMap<>();
        Map<LocalDate, Set<String>> postedRrns = new HashMap<>();
        for (SourceRecordEntity s : records.findByDataset(ACC_RRN_POSTINGS)) {
            JsonNode p = read(s.payload);
            String rrn = p.path("query").path("rrn").asText();
            CsTxnEntity t = byRrn.get(rrn);
            if (t == null) continue;
            for (JsonNode row : p.path("rows")) {
                String tt = Optional.ofNullable(f.text(row, "posting.ttCode")).orElse(f.text(row, "posting.subModule"));
                if (Filters.has(flt.ttCode()) && !flt.ttCode().equalsIgnoreCase(tt)) continue;
                List<Object> k = List.of(String.valueOf(t.salesDate), String.valueOf(tt),
                        String.valueOf(f.text(row, "posting.ledger")));
                BigDecimal[] a = agg.computeIfAbsent(k, x -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                a[0] = a[0].add(f.decimal(row, "posting.debit").orElse(BigDecimal.ZERO));
                a[1] = a[1].add(f.decimal(row, "posting.credit").orElse(BigDecimal.ZERO));
                lines.merge(k, 1L, Long::sum);
                postedRrns.computeIfAbsent(t.salesDate, x -> new HashSet<>()).add(rrn);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        agg.forEach((k, a) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", k.get(0));
            m.put("ttOrSubModule", k.get(1));
            m.put("ledger", k.get(2));
            m.put("postingLines", lines.get(k));
            m.put("debit", a[0]);
            m.put("credit", a[1]);
            LocalDate d = Fields.parseDate((String) k.get(0)).orElse(null);
            m.put("csTransactionsOnDate", d == null ? null : csCount.get(d));
            m.put("transactionsWithPostingsOnDate", d == null ? null : postedRrns.getOrDefault(d, Set.of()).size());
            m.put("_system", "ACCOUNTING");
            m.put("_endpoint", "/api/accounting/searchAllEntries");
            out.add(m);
        });
        return out;
    }

    public List<Map<String, Object>> mappings() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TtMappingEntity m : mappings.findAllByOrderByCodeAsc()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", m.code);
            r.put("name", m.name);
            r.put("glLines", tree(m.glLines));
            r.put("status", m.status);
            r.put("displayStatus", "CONFIRMED".equals(m.status) ? "Confirmed" : "Mapping not confirmed");
            r.put("confirmedBy", m.confirmedBy);
            r.put("confirmedAt", m.confirmedAt);
            r.put("note", m.note);
            r.put("_system", "ACCOUNTING");
            r.put("_endpoint", "/api/transactiontype/getall");
            r.put("_screen", "#/configurations/transactiontype-getall");
            r.put("_recordKey", m.sourceRecordKey);
            r.put("_retrievedAt", m.retrievedAt);
            out.add(r);
        }
        return out;
    }

    // ---------------------------------------------------------------- ledger health

    public List<Map<String, Object>> trialBalance(String category) {
        List<Map<String, Object>> out = new ArrayList<>();
        Long run = snapshots.findFirstByDatasetAndOkTrueOrderByIdDesc(ACC_TRIAL_BALANCE).map(s -> s.runId).orElse(null);
        if (run == null) return out;
        for (SourceRecordEntity s : records.findByDatasetAndLastRunId(ACC_TRIAL_BALANCE, run)) {
            JsonNode p = read(s.payload);
            JsonNode q = p.path("query");
            if (Filters.has(category) && !category.equalsIgnoreCase(q.path("category").asText())) continue;
            for (JsonNode row : p.path("rows")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("category", q.path("category").asText());
                m.put("fromDate", q.path("fromDate").asText());
                m.put("toDate", q.path("toDate").asText());
                m.put("accountGroup", row.path("accountGroup").asText(null));
                m.put("subGroup", row.path("subGroup").asText(null));
                m.put("accountName", row.path("accountName").asText(null));
                m.put("debit", f.decimal(row, "tb.debit").orElse(null));
                m.put("credit", f.decimal(row, "tb.credit").orElse(null));
                m.putAll(source(s));
                out.add(m);
            }
        }
        return out;
    }

    public List<Map<String, Object>> datasetRows(String ds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SourceRecordEntity r : records.findByDataset(ds)) {
            Map<String, Object> m = new LinkedHashMap<>();
            Object t = tree(r.payload);
            if (t instanceof Map<?, ?> map) map.forEach((k, v) -> m.put(String.valueOf(k), v));
            m.putAll(source(r));
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- runs & alerts

    public List<Map<String, Object>> runs(int limit) {
        return runs.findAllByOrderByIdDesc(PageRequest.of(0, limit)).stream().map(this::runRow).toList();
    }

    public Map<String, Object> runRow(RunEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("trigger", r.triggerType);
        m.put("groups", r.groupsCsv);
        m.put("windowFrom", r.windowFrom);
        m.put("windowTo", r.windowTo);
        m.put("startedAt", r.startedAt);
        m.put("finishedAt", r.finishedAt);
        m.put("durationSeconds", r.finishedAt == null ? null
                : java.time.Duration.between(r.startedAt, r.finishedAt).toSeconds());
        m.put("status", r.status);
        m.put("recordsCollected", r.recordsCollected);
        m.put("checksPassed", r.checksPassed);
        m.put("checksFailed", r.checksFailed);
        m.put("rulesNotEvaluated", r.checksNotEvaluated);
        m.put("error", r.error);
        m.put("triggeredBy", r.triggeredBy);
        return m;
    }

    public Map<String, Object> runDetail(Long id) {
        RunEntity r = runs.findById(id).orElseThrow(() -> new NoSuchElementException("No run " + id));
        Map<String, Object> m = runRow(r);
        m.put("datasets", snapshots.findByRunId(id).stream().map(s -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("dataset", s.dataset);
            x.put("system", s.systemName);
            x.put("endpoint", s.endpoint);
            x.put("requestParams", s.requestParams);
            x.put("ok", s.ok);
            x.put("error", s.error);
            x.put("records", s.recordCount);
            x.put("totalReported", s.totalReported);
            x.put("truncated", s.truncated);
            x.put("retrievedAt", s.retrievedAt);
            return x;
        }).toList());
        List<ApiCallEntity> log = calls.findByRunIdOrderByIdAsc(id);
        m.put("networkLog", log.stream().map(c -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("at", c.at);
            x.put("system", c.systemName);
            x.put("method", c.method);
            x.put("path", c.path);
            x.put("http", c.httpStatus);
            x.put("success", c.envelopeSuccess);
            x.put("statusCode", c.envelopeStatusCode);
            x.put("ms", c.durationMs);
            x.put("blocked", c.blocked);
            x.put("error", c.error);
            return x;
        }).toList());
        Set<String> paths = log.stream().map(c -> c.path).collect(Collectors.toCollection(TreeSet::new));
        m.put("distinctPaths", paths);
        m.put("writeCallsAttempted", log.stream().filter(c -> c.blocked).count());
        m.put("allPathsAllowlisted", paths.stream().allMatch(in.toucan.monitor.collector.ReadOnlyGuard::isAllowed));
        return m;
    }

    public List<Map<String, Object>> alerts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DatasetSnapshotEntity s : snapshots.latestFailures()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", "HIGH");
            m.put("type", "SOURCE_UNAVAILABLE");
            m.put("ruleId", null);
            m.put("subject", s.dataset);
            m.put("message", s.error);
            m.put("at", s.retrievedAt);
            m.put("_endpoint", s.endpoint);
            m.put("_requestParams", s.requestParams);
            out.add(m);
        }
        for (RuleResultEntity r : latestResults(null)) {
            if ("SUMMARY".equals(r.subjectKey)) continue;
            if (!r.outcome.equals("FAIL") && !r.outcome.equals("MISSING")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", r.category.equals("ACCOUNTING_INTERNAL") || r.outcome.equals("MISSING") ? "HIGH" : "MEDIUM");
            m.put("type", r.outcome);
            m.put("ruleId", r.ruleId);
            m.put("subject", r.subjectKey);
            m.put("message", r.message);
            m.put("at", r.createdAt);
            m.put("processor", r.processor);
            m.put("evidence", tree(r.evidence));
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- data quality

    public Map<String, Object> dataQuality() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decryptionFailures", validation("R07", "FAIL", null, new Filters(null, null, null, null, null,
                null, null, null, null, null, null, null, null), false));
        out.put("sourceErrors", calls.failures(PageRequest.of(0, 200)).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", c.at);
            m.put("system", c.systemName);
            m.put("path", c.path);
            m.put("http", c.httpStatus);
            m.put("success", c.envelopeSuccess);
            m.put("statusCode", c.envelopeStatusCode);
            m.put("note", c.httpStatus != null && c.httpStatus == 200 && Boolean.FALSE.equals(c.envelopeSuccess)
                    ? "HTTP 200 with success:false (e.g. NOTUSED)" : c.error);
            m.put("runId", c.runId);
            return m;
        }).toList());
        out.put("datasetErrors", snapshots.latestFailures().stream().map(s -> Map.of(
                "dataset", s.dataset, "error", String.valueOf(s.error), "at", String.valueOf(s.retrievedAt))).toList());
        out.put("labelMismatches", LABEL_MISMATCHES);
        out.put("dateFormats", dateFormats());
        return out;
    }

    /** Which date formats appear per dataset/field; more than one format or unparseable values are anomalies. */
    List<Map<String, Object>> dateFormats() {
        Map<String, String> fieldsByDs = new LinkedHashMap<>();
        fieldsByDs.put(ACC_PERIODS, "period.start");
        fieldsByDs.put(ACC_FISCAL_YEARS, "fy.start");
        fieldsByDs.put(CS_JV, "jv.date");
        fieldsByDs.put(CS_SCHEME_FILES, "file.date");
        fieldsByDs.put(CS_UPI_SUMMARY_BDF, "bdf.date");
        List<Map<String, Object>> out = new ArrayList<>();
        fieldsByDs.forEach((ds, key) -> {
            Map<String, Integer> shapes = new TreeMap<>();
            int bad = 0;
            for (SourceRecordEntity r : records.findByDataset(ds)) {
                String v = f.text(read(r.payload), key);
                if (v == null) continue;
                shapes.merge(v.replaceAll("\\d", "9"), 1, Integer::sum);
                if (Fields.parseDate(v).isEmpty()) bad++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataset", ds);
            m.put("field", key);
            m.put("formats", shapes);
            m.put("unparseable", bad);
            m.put("anomaly", shapes.size() > 1 || bad > 0);
            out.add(m);
        });
        return out;
    }

    public List<Map<String, Object>> processors() {
        return processors.findAllByOrderByCodeAsc().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.code);
            m.put("displayName", p.displayName);
            m.put("active", p.active);
            m.put("firstSeenAt", p.firstSeenAt);
            return m;
        }).toList();
    }
}
