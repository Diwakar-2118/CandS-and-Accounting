package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** A processor (platformType) discovered in C&S data. Display name and active flag are editable config. */
@Entity
@Table(name = "processor_config")
public class ProcessorEntity {
    @Id
    public String code;
    @Column(name = "display_name")
    public String displayName;
    public boolean active;
    @Column(name = "first_seen_at")
    public Instant firstSeenAt;
}
