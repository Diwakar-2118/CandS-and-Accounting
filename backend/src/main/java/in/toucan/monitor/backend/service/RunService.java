package in.toucan.monitor.backend.service;

import in.toucan.monitor.backend.config.MonitorProperties;
import in.toucan.monitor.backend.domain.RunEntity;
import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.collector.ToucanConfig;
import in.toucan.monitor.engine.Fields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Orchestrates collect -> map -> validate runs, on schedule or on demand. One run at a time. */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final CollectionService collection;
    private final MappingService mappings;
    private final ValidationService validation;
    private final ApiCallRecorder recorder;
    private final Repos.RunRepo runs;
    private final AuditService audit;
    private final MonitorProperties props;
    private final ToucanConfig toucan;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "monitor-run");
        t.setDaemon(true);
        return t;
    });

    public RunService(CollectionService collection, MappingService mappings, ValidationService validation,
                      ApiCallRecorder recorder, Repos.RunRepo runs, AuditService audit, MonitorProperties props,
                      ToucanConfig toucan, Clock clock) {
        this.collection = collection;
        this.mappings = mappings;
        this.validation = validation;
        this.recorder = recorder;
        this.runs = runs;
        this.audit = audit;
        this.props = props;
        this.toucan = toucan;
        this.clock = clock;
    }

    /** A run still RUNNING at startup was cut off by a restart: mark it so it doesn't look live forever. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void abortInterruptedRuns() {
        runs.findAll().stream().filter(r -> "RUNNING".equals(r.status)).forEach(r -> {
            r.status = "ABORTED";
            r.finishedAt = Instant.now(clock);
            r.error = (r.error == null ? "" : r.error + " ") + "Interrupted by an application restart.";
            runs.save(r);
            log.warn("Marked interrupted run {} as ABORTED", r.id);
        });
    }

    public boolean isRunning() {
        return running.get();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(Fields.zone()));
    }

    /** Starts a run in the background; returns the run id, or throws if one is already running. */
    public Long start(String trigger, Set<CollectionService.Group> groups, LocalDate from, LocalDate to, String user) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("A run is already in progress");
        RunEntity run = new RunEntity();
        try {
            run.triggerType = trigger;
            run.groupsCsv = groups.stream().map(Enum::name).collect(Collectors.joining(","));
            run.windowFrom = from;
            run.windowTo = to;
            run.startedAt = clock.instant();
            run.status = "RUNNING";
            run.triggeredBy = user;
            run = runs.save(run);
            audit.log(user, "RUN_START", "run " + run.id + " " + trigger + " " + run.groupsCsv + " " + from + ".." + to);
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        Long id = run.id;
        executor.submit(() -> execute(id, groups, from, to));
        return id;
    }

    private void execute(Long runId, Set<CollectionService.Group> groups, LocalDate from, LocalDate to) {
        RunEntity run = runs.findById(runId).orElseThrow();
        recorder.bind(runId);
        int collected = 0;
        StringBuilder errors = new StringBuilder();
        try {
            if (!toucan.hasCredentials()) {
                errors.append("TOUCAN_USERNAME/TOUCAN_PASSWORD not set: no source data collected. ");
            } else {
                for (CollectionService.Group g : groups) {
                    try {
                        collected += collection.collect(g, runId, from, to);
                    } catch (RuntimeException e) {
                        log.error("Group {} failed", g, e);
                        errors.append(g).append(": ").append(e.getMessage()).append(". ");
                    }
                }
                if (groups.contains(CollectionService.Group.LEDGER)) mappings.syncFromAccounting();
            }
            ValidationService.Summary s = validation.validate(runId, from, to);
            run.checksPassed = s.passed();
            run.checksFailed = s.failed();
            run.checksNotEvaluated = s.notEvaluated();
            run.status = errors.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
        } catch (RuntimeException e) {
            log.error("Run {} failed", runId, e);
            errors.append(e.getMessage());
            run.status = "FAILED";
        } finally {
            try {
                run.recordsCollected = collected;
                run.finishedAt = Instant.now(clock);
                run.error = errors.isEmpty() ? null : errors.toString();
                runs.save(run);
            } catch (RuntimeException e) {
                log.error("Could not record the end of run {}", runId, e);
            } finally {
                recorder.bind(null);
                running.set(false); // never leave the lock held, or no run could start again
            }
        }
    }

    public LocalDate defaultFrom() {
        return today().minusDays(props.getWindowDays() - 1L);
    }

    private void scheduled(Set<CollectionService.Group> groups) {
        if (!props.getSchedule().isEnabled()) return;
        if (running.get()) {
            log.info("Skipping scheduled {}: a run is in progress", groups);
            return;
        }
        try {
            start("SCHEDULED", groups, defaultFrom(), today(), "scheduler");
        } catch (IllegalStateException e) {
            log.info("Skipped scheduled run: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${monitor.schedule.transactions}", zone = "Asia/Kolkata")
    public void scheduledTransactions() {
        scheduled(EnumSet.of(CollectionService.Group.TRANSACTIONS));
    }

    @Scheduled(cron = "${monitor.schedule.files}", zone = "Asia/Kolkata")
    public void scheduledFiles() {
        scheduled(EnumSet.of(CollectionService.Group.FILES));
    }

    @Scheduled(cron = "${monitor.schedule.ledger}", zone = "Asia/Kolkata")
    public void scheduledLedger() {
        scheduled(EnumSet.of(CollectionService.Group.LEDGER));
    }
}
