package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** Previous versions of a source record, written whenever its content hash changes. */
@Entity
@Table(name = "source_record_history")
public class SourceRecordHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "source_record_id")
    public Long sourceRecordId;
    @Column(name = "content_hash")
    public String contentHash;
    public String payload;
    @Column(name = "retrieved_at")
    public Instant retrievedAt;
    @Column(name = "run_id")
    public Long runId;
}
