package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** A Transaction Type read from Accounting, with its business confirmation status. */
@Entity
@Table(name = "tt_mapping")
public class TtMappingEntity {
    @Id
    public String code;
    public String name;
    /** JSON array of {account, side}. */
    @Column(name = "gl_lines")
    public String glLines;
    /** CONFIRMED or UNCONFIRMED. */
    public String status;
    @Column(name = "source_record_key")
    public String sourceRecordKey;
    @Column(name = "source_hash")
    public String sourceHash;
    @Column(name = "retrieved_at")
    public Instant retrievedAt;
    @Column(name = "confirmed_by")
    public String confirmedBy;
    @Column(name = "confirmed_at")
    public Instant confirmedAt;
    public String note;
}
