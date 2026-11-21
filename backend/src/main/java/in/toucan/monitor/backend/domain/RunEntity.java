package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "monitor_run")
public class RunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "trigger_type")
    public String triggerType;
    @Column(name = "groups_csv")
    public String groupsCsv;
    @Column(name = "window_from")
    public LocalDate windowFrom;
    @Column(name = "window_to")
    public LocalDate windowTo;
    @Column(name = "started_at")
    public Instant startedAt;
    @Column(name = "finished_at")
    public Instant finishedAt;
    public String status;
    @Column(name = "records_collected")
    public Integer recordsCollected;
    @Column(name = "checks_passed")
    public Integer checksPassed;
    @Column(name = "checks_failed")
    public Integer checksFailed;
    @Column(name = "checks_not_evaluated")
    public Integer checksNotEvaluated;
    public String error;
    @Column(name = "triggered_by")
    public String triggeredBy;
}
