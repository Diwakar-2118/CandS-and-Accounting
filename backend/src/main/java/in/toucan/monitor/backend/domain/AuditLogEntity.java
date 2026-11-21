package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Instant at;
    public String username;
    public String action;
    public String details;
}
