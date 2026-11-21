package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** One collection attempt of one dataset query. Drives "Not available" and "last successful run". */
@Entity
@Table(name = "dataset_snapshot")
public class DatasetSnapshotEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "run_id")
    public Long runId;
    public String dataset;
    @Column(name = "system_name")
    public String systemName;
    public String endpoint;
    @Column(name = "request_params")
    public String requestParams;
    public boolean ok;
    public String error;
    @Column(name = "record_count")
    public int recordCount;
    @Column(name = "total_reported")
    public Long totalReported;
    public boolean truncated;
    @Column(name = "retrieved_at")
    public Instant retrievedAt;
}
