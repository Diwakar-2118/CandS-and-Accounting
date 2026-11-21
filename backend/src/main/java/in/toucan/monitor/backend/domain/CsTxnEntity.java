package in.toucan.monitor.backend.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Typed projection of a C&S transaction record (API field names, not UI labels). */
@Entity
@Table(name = "cs_txn")
public class CsTxnEntity {
    @Id
    @Column(name = "source_record_id")
    public Long sourceRecordId;
    @Column(name = "natural_key")
    public String naturalKey;
    public String rrn;
    public String arn;
    @Column(name = "invoice_number")
    public String invoiceNumber;
    @Column(name = "merchant_number")
    public String merchantNumber;
    @Column(name = "merchant_name")
    public String merchantName;
    @Column(name = "terminal_number")
    public String terminalNumber;
    @Column(name = "run_number")
    public String runNumber;
    @Column(name = "scheme_code")
    public String schemeCode;
    @Column(name = "platform_type")
    public String platformType;
    @Column(name = "transaction_code")
    public String transactionCode;
    @Column(name = "settlement_status")
    public String settlementStatus;
    @Column(name = "life_cycle_status")
    public String lifeCycleStatus;
    @Column(name = "transaction_status")
    public String transactionStatus;
    @Column(name = "txn_status")
    public String txnStatus;
    @Column(name = "payment_release_status")
    public String paymentReleaseStatus;
    @Column(name = "db_or_cr")
    public String dbOrCr;
    @Column(name = "txn_amount")
    public BigDecimal txnAmount;
    @Column(name = "net_amount")
    public BigDecimal netAmount;
    @Column(name = "sales_date_time")
    public String salesDateTime;
    @Column(name = "sales_date")
    public LocalDate salesDate;
    /** COMPLETED / PENDING / MISSING / FAILED / NOT_CHECKED (from rules R08 and R10). */
    @Column(name = "accounting_status")
    public String accountingStatus;
    @Column(name = "accounting_checked_at")
    public Instant accountingCheckedAt;
}
