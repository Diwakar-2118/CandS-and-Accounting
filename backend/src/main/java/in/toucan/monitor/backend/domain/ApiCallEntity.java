package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** Network log of every outbound call: the evidence that a run made no write calls. */
@Entity
@Table(name = "api_call")
public class ApiCallEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "run_id")
    public Long runId;
    public Instant at;
    @Column(name = "system_name")
    public String systemName;
    public String method;
    public String path;
    @Column(name = "http_status")
    public Integer httpStatus;
    @Column(name = "envelope_success")
    public Boolean envelopeSuccess;
    @Column(name = "envelope_status_code")
    public Integer envelopeStatusCode;
    @Column(name = "duration_ms")
    public Long durationMs;
    public boolean blocked;
    public String error;
}
