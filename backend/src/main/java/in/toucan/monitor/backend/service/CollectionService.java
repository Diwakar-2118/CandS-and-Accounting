package in.toucan.monitor.backend.service;

import in.toucan.monitor.backend.config.MonitorProperties;
import in.toucan.monitor.backend.domain.CsTxnEntity;
import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.collector.CollectionResult;
import in.toucan.monitor.collector.DatasetSpec;
import in.toucan.monitor.collector.Datasets;
import in.toucan.monitor.collector.ToucanClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static in.toucan.monitor.collector.Datasets.*;

/** Pulls each dataset group from the UAT APIs and stores it. Read-only by construction (ReadOnlyGuard). */
@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    /** Declared in run order: quick groups first so the dashboard fills before the slow per-record look-ups. */
    public enum Group {
        /** Accounting reference data, trial balance, adjustments, periods, ledger statements. */
        LEDGER,
        /** Scheme/payment files, reports, processed files, JV, UPI BDF, bulk downloads, payment exceptions. */
        FILES,
        /** C&S transactions, payments, holds/reserves, rejects + Accounting postings by RRN. */
        TRANSACTIONS
    }

    private final ToucanClients clients;
    private final IngestionService ingestion;
    private final Repos.TxnRepo txns;
    private final MonitorProperties props;

    public CollectionService(ToucanClients clients, IngestionService ingestion, Repos.TxnRepo txns,
                             MonitorProperties props) {
        this.clients = clients;
        this.ingestion = ingestion;
        this.txns = txns;
        this.props = props;
    }

    /** @return number of records stored */
    public int collect(Group g, Long runId, LocalDate from, LocalDate to) {
        return switch (g) {
            case TRANSACTIONS -> transactions(runId, from, to);
            case FILES -> files(runId, from, to);
            case LEDGER -> ledger(runId, from, to);
        };
    }

    private int one(String dataset, Long runId, LocalDate from, LocalDate to, Map<String, Object> extra) {
        DatasetSpec spec = Datasets.get(dataset);
        CollectionResult r = clients.collector.collect(spec, from, to, extra);
        if (!r.ok()) log.warn("{} unavailable: {}", dataset, r.error());
        return ingestion.store(r, runId);
    }

    private int transactions(Long runId, LocalDate from, LocalDate to) {
        int n = one(CS_TRANSACTIONS, runId, from, to, Map.of());
        n += one(CS_ROLLING_RESERVE, runId, from, to, Map.of());
        n += one(CS_INSTANT_EXCEPTIONS, runId, from, to, Map.of());
        n += one(CS_SCHEME_REJECTS, runId, from, to, Map.of());
        n += one(CS_PAYMENTS_CREDIT, runId, from, to, Map.of());
        n += one(CS_PAYMENTS_DEBIT, runId, from, to, Map.of());

        // Accounting postings per RRN first (drives the accounting-status KPI), for transactions not yet
        // confirmed as posted, oldest first.
        List<CsTxnEntity> pending = txns.needingAccountingLookup(from, to,
                PageRequest.of(0, props.getRrnLookupsPerRun()));
        Instant now = Instant.now();
        for (CsTxnEntity t : pending) {
            n += one(ACC_RRN_POSTINGS, runId, null, null, Map.of("rrn", t.rrn));
            t.accountingCheckedAt = now;
        }
        txns.saveAll(pending);

        // Transactions behind payments: one look-up per (run, MID, side) seen on released transactions.
        Set<List<String>> paymentKeys = new LinkedHashSet<>();
        for (CsTxnEntity t : txns.findBySalesDateBetween(from, to)) {
            if (t.runNumber != null && t.merchantNumber != null && t.dbOrCr != null
                    && "COMPLETED".equalsIgnoreCase(t.paymentReleaseStatus)) {
                paymentKeys.add(List.of(t.runNumber, t.merchantNumber, t.dbOrCr.toUpperCase()));
            }
            if (paymentKeys.size() >= props.getPaymentTxnLookupsPerRun()) break;
        }
        for (List<String> k : paymentKeys) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("runNumber", k.get(0));
            q.put("merchantNumber", k.get(1));
            q.put("dbOrcrToMerchant", k.get(2));
            n += one(CS_PAYMENT_TXNS, runId, null, null, q);
        }
        return n;
    }

    private int files(Long runId, LocalDate from, LocalDate to) {
        int n = 0;
        for (String ds : List.of(CS_SCHEME_FILES, CS_PAYMENT_FILES, CS_REPORTS, CS_PROCESSED_FILES, CS_JV,
                CS_BULK_DOWNLOAD, CS_UPI_SUMMARY_BDF, CS_PAYMENT_EXCEPTIONS)) {
            n += one(ds, runId, from, to, Map.of());
        }
        return n;
    }

    private int ledger(Long runId, LocalDate from, LocalDate to) {
        int n = 0;
        for (String ds : List.of(ACC_TXN_TYPES, ACC_LEDGERS, ACC_GROUPS, ACC_SUBGROUPS, ACC_PERIODS,
                ACC_FISCAL_YEARS, ACC_LEDGER_ADJUSTMENTS)) {
            n += one(ds, runId, null, null, Map.of());
        }
        // Trial balance for the window, by the three categories the screen offers.
        for (String category : List.of("group", "subgroup", "ledger")) {
            n += one(ACC_TRIAL_BALANCE, runId, from, to, Map.of("category", category));
        }
        // Month-to-date as well, matching how the screen is usually read.
        LocalDate monthStart = to.withDayOfMonth(1);
        if (monthStart.isBefore(from)) {
            n += one(ACC_TRIAL_BALANCE, runId, monthStart, to, Map.of("category", "group"));
        }
        for (String ledger : props.getStatementLedgers()) {
            n += one(ACC_LEDGER_STATEMENT, runId, from, to, Map.of("entryType", ledger));
        }
        n += one(ACC_MERCHANT_LEDGER, runId, from, to, Map.of());
        return n;
    }
}
