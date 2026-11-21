package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "rule_result")
public class RuleResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "run_id")
    public Long runId;
    @Column(name = "rule_id")
    public String ruleId;
    @Column(name = "rule_name")
    public String ruleName;
    public String category;
    @Column(name = "subject_key")
    public String subjectKey;
    public String outcome;
    public String message;
    public String processor;
    @Column(name = "business_date")
    public LocalDate businessDate;
    @Column(name = "tt_code")
    public String ttCode;
    public String ledger;
    /** JSON: expected, actual, sources[], details. */
    public String evidence;
    @Column(name = "created_at")
    public Instant createdAt;
}
