package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "source_record")
public class SourceRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String dataset;
    @Column(name = "system_name")
    public String systemName;
    public String endpoint;
    @Column(name = "natural_key")
    public String naturalKey;
    @Column(name = "content_hash")
    public String contentHash;
    /** Masked JSON payload (card and account numbers are masked before storage). */
    public String payload;
    @Column(name = "request_params")
    public String requestParams;
    @Column(name = "first_seen_at")
    public Instant firstSeenAt;
    @Column(name = "last_seen_at")
    public Instant lastSeenAt;
    @Column(name = "retrieved_at")
    public Instant retrievedAt;
    @Column(name = "last_run_id")
    public Long lastRunId;
}
