package in.toucan.monitor.backend.web;

import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.backend.service.*;
import in.toucan.monitor.collector.Datasets;
import in.toucan.monitor.engine.RuleEngine;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

/** Dashboard REST API. Read-only towards Toucan; the only writes go to the monitor's own database. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final QueryService q;
    private final KpiService kpis;
    private final RunService runs;
    private final MappingService mappings;
    private final ExportService export;
    private final AuditService audit;
    private final Repos.ProcessorRepo processors;
    private final Repos.AuditRepo auditRepo;

    public ApiController(QueryService q, KpiService kpis, RunService runs, MappingService mappings,
                         ExportService export, AuditService audit, Repos.ProcessorRepo processors,
                         Repos.AuditRepo auditRepo) {
        this.q = q;
        this.kpis = kpis;
        this.runs = runs;
        this.mappings = mappings;
        this.export = export;
        this.audit = audit;
        this.processors = processors;
        this.auditRepo = auditRepo;
    }

    /** Builds filters; window defaults to the configured window ending today (IST). */
    Filters filters(Map<String, String> p) {
        LocalDate to = p.containsKey("to") && !p.get("to").isBlank() ? LocalDate.parse(p.get("to")) : runs.today();
        LocalDate from = p.containsKey("from") && !p.get("from").isBlank() ? LocalDate.parse(p.get("from"))
                : runs.defaultFrom();
        return new Filters(from, to, p.get("processor"), p.get("scheme"), p.get("channel"), p.get("merchant"),
                p.get("txnStatus"), p.get("accountingStatus"), p.get("ttCode"), p.get("fileType"), p.get("q"),
                p.get("settlementStatus"), p.get("paymentReleaseStatus"));
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", runs.today());
        m.put("defaultFrom", runs.defaultFrom());
        m.put("running", runs.isRunning());
        m.put("processors", q.processors());
        m.put("channels", Filters.CHANNELS);
        m.put("fileCategories", QueryService.FILE_CATEGORIES.keySet());
        m.put("rules", RuleEngine.standard().rules().stream().map(r -> Map.of("id", r.id(), "name", r.name(),
                "category", r.category())).toList());
        m.put("datasets", Datasets.all().stream().map(d -> q.stateMap(d.key())).toList());
        m.put("labelMismatches", QueryService.LABEL_MISMATCHES);
        return m;
    }

    @GetMapping("/kpis")
    public List<Map<String, Object>> kpis(@RequestParam Map<String, String> p) {
        return kpis.overview(filters(p));
    }

    @GetMapping("/charts")
    public Map<String, Object> charts(@RequestParam Map<String, String> p) {
        return kpis.charts(filters(p));
    }

    @GetMapping("/transactions")
    public Page<Map<String, Object>> transactions(@RequestParam Map<String, String> p,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return q.transactions(filters(p), page, size);
    }

    @GetMapping("/transactions/{recordId}/lifecycle")
    public Map<String, Object> lifecycle(@PathVariable Long recordId) {
        return q.lifecycle(recordId);
    }

    @GetMapping("/files")
    public List<Map<String, Object>> files(@RequestParam Map<String, String> p, @RequestParam String category,
                                           @RequestParam(required = false) String status) {
        return q.files(category, filters(p), status);
    }

    @GetMapping("/validation/results")
    public List<Map<String, Object>> results(@RequestParam Map<String, String> p,
                                             @RequestParam(required = false) String ruleId,
                                             @RequestParam(required = false) String outcome,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(defaultValue = "false") boolean summary) {
        return q.validation(ruleId, outcome, category, filters(p), summary);
    }

    @GetMapping("/validation/summary")
    public List<Map<String, Object>> ruleSummary(@RequestParam Map<String, String> p) {
        return q.validation(null, null, null, filters(p), true).stream()
                .filter(r -> "SUMMARY".equals(r.get("subjectKey"))).toList();
    }

    @GetMapping("/validation/matrix")
    public List<Map<String, Object>> matrix(@RequestParam Map<String, String> p) {
        return q.matrix(filters(p));
    }

    @GetMapping("/mappings")
    public List<Map<String, Object>> mappings() {
        return q.mappings();
    }

    @PostMapping("/mappings/{code}/confirm")
    public Map<String, Object> confirm(@PathVariable String code, @RequestBody(required = false) Map<String, String> b) {
        mappings.setStatus(code, true, b == null ? null : b.get("note"));
        return Map.of("code", code, "status", "CONFIRMED");
    }

    @PostMapping("/mappings/{code}/unconfirm")
    public Map<String, Object> unconfirm(@PathVariable String code, @RequestBody(required = false) Map<String, String> b) {
        mappings.setStatus(code, false, b == null ? null : b.get("note"));
        return Map.of("code", code, "status", "UNCONFIRMED");
    }

    @GetMapping("/ledger/trial-balance")
    public List<Map<String, Object>> trialBalance(@RequestParam(required = false) String category) {
        return q.trialBalance(category);
    }

    @GetMapping("/ledger/adjustments")
    public List<Map<String, Object>> adjustments() {
        return q.datasetRows(Datasets.ACC_LEDGER_ADJUSTMENTS);
    }

    @GetMapping("/ledger/periods")
    public Map<String, Object> periods() {
        return Map.of("periods", q.datasetRows(Datasets.ACC_PERIODS),
                "fiscalYears", q.datasetRows(Datasets.ACC_FISCAL_YEARS));
    }

    @GetMapping("/recon/payu")
    public List<Map<String, Object>> payu(@RequestParam Map<String, String> p) {
        return q.validation("R16", null, null, filters(p), true);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runList(@RequestParam(defaultValue = "50") int limit) {
        return q.runs(Math.min(limit, 500));
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable Long id) {
        return q.runDetail(id);
    }

    /** Manual "Run validation". Body: {groups:[TRANSACTIONS,FILES,LEDGER], from, to}. */
    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> trigger(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Set<CollectionService.Group> groups = EnumSet.noneOf(CollectionService.Group.class);
        Object g = b.get("groups");
        if (g instanceof List<?> l) l.forEach(x -> groups.add(CollectionService.Group.valueOf(String.valueOf(x))));
        if (groups.isEmpty()) groups.addAll(EnumSet.allOf(CollectionService.Group.class));
        LocalDate from = b.get("from") == null ? runs.defaultFrom() : LocalDate.parse(String.valueOf(b.get("from")));
        LocalDate to = b.get("to") == null ? runs.today() : LocalDate.parse(String.valueOf(b.get("to")));
        if (from.isAfter(to)) throw new IllegalArgumentException("from is after to");
        Long id = runs.start("MANUAL", groups, from, to, AuditService.currentUser());
        return ResponseEntity.accepted().body(Map.of("runId", id));
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts() {
        return q.alerts();
    }

    @GetMapping("/data-quality")
    public Map<String, Object> dataQuality() {
        return q.dataQuality();
    }

    @GetMapping("/processors")
    public List<Map<String, Object>> processorList() {
        return q.processors();
    }

    @PutMapping("/processors/{code}")
    public Map<String, Object> updateProcessor(@PathVariable String code, @RequestBody Map<String, Object> b) {
        var p = processors.findById(code).orElseThrow(() -> new NoSuchElementException("No processor " + code));
        if (b.get("displayName") != null) p.displayName = String.valueOf(b.get("displayName"));
        if (b.get("active") != null) p.active = Boolean.parseBoolean(String.valueOf(b.get("active")));
        processors.save(p);
        audit.log("PROCESSOR_UPDATE", code + " " + b);
        return Map.of("code", code);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog(@RequestParam(defaultValue = "200") int limit) {
        return auditRepo.findAllByOrderByIdDesc(org.springframework.data.domain.PageRequest.of(0, Math.min(limit, 1000)))
                .stream().map(a -> Map.<String, Object>of("at", a.at, "user", a.username, "action", a.action,
                        "details", String.valueOf(a.details))).toList();
    }

    /** Export any list. list = transactions|files|validation|matrix|mappings|trial-balance|adjustments|periods|runs|alerts|payu. */
    @GetMapping("/export/{list}")
    public void export(@PathVariable String list, @RequestParam(defaultValue = "csv") String format,
                       @RequestParam Map<String, String> p, HttpServletResponse resp) throws IOException {
        Filters f = filters(p);
        List<Map<String, Object>> rows = switch (list) {
            case "transactions" -> q.transactionsAll(f).stream().map(q::transactionRow).toList();
            case "files" -> q.files(p.getOrDefault("category", "scheme"), f, p.get("status"));
            case "validation" -> q.validation(p.get("ruleId"), p.get("outcome"), p.get("category"), f,
                    Boolean.parseBoolean(p.getOrDefault("summary", "false")));
            case "matrix" -> q.matrix(f);
            case "mappings" -> q.mappings();
            case "trial-balance" -> q.trialBalance(p.get("tbCategory"));
            case "adjustments" -> q.datasetRows(Datasets.ACC_LEDGER_ADJUSTMENTS);
            case "periods" -> q.datasetRows(Datasets.ACC_PERIODS);
            case "runs" -> q.runs(500);
            case "alerts" -> q.alerts();
            case "payu" -> q.validation("R16", null, null, f, true);
            default -> throw new IllegalArgumentException("Unknown list " + list);
        };
        audit.log("EXPORT", list + " " + format + " rows=" + rows.size() + " " + f);
        String name = list + "-" + f.from() + "_" + f.to();
        if ("xlsx".equalsIgnoreCase(format)) {
            resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + name + ".xlsx\"");
            export.xlsx(rows, list, resp.getOutputStream());
        } else {
            resp.setContentType("text/csv; charset=utf-8");
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + name + ".csv\"");
            export.csv(rows, resp.getOutputStream());
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
    public ResponseEntity<Map<String, String>> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
