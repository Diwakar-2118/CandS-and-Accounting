package in.toucan.monitor.collector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static in.toucan.monitor.collector.BodyStyle.*;
import static in.toucan.monitor.collector.ToucanSystem.ACCOUNTING;
import static in.toucan.monitor.collector.ToucanSystem.CANDS;

/**
 * Catalog of every dataset the monitor reads. Every path, body style and fixed filter below was
 * observed in the CandS/Accounting Angular bundles during Phase 1 (docs/phase1-discovery.md).
 * Response field names are confirmed at runtime by the discovery CLI, not assumed here.
 */
public final class Datasets {

    // ---- CandS ----
    public static final String CS_TRANSACTIONS = "cs.transactions";
    public static final String CS_ROLLING_RESERVE = "cs.rolling_reserve";
    public static final String CS_INSTANT_EXCEPTIONS = "cs.instant_settlement_exceptions";
    public static final String CS_SCHEME_FILES = "cs.scheme_files";
    public static final String CS_PAYMENT_FILES = "cs.payment_files";
    public static final String CS_REPORTS = "cs.reports";
    public static final String CS_PROCESSED_FILES = "cs.processed_files";
    public static final String CS_SCHEME_REJECTS = "cs.scheme_rejects";
    public static final String CS_PAYMENTS_CREDIT = "cs.payments_credit";
    public static final String CS_PAYMENTS_DEBIT = "cs.payments_debit";
    public static final String CS_PAYMENT_TXNS = "cs.payment_txns";
    public static final String CS_JV = "cs.journal_vouchers";
    public static final String CS_BULK_DOWNLOAD = "cs.bulk_download";
    public static final String CS_UPI_SUMMARY_BDF = "cs.upi_summary_bdf";
    public static final String CS_PAYMENT_EXCEPTIONS = "cs.payment_exceptions";
    // ---- Accounting ----
    public static final String ACC_TXN_TYPES = "acc.transaction_types";
    public static final String ACC_LEDGERS = "acc.chart_of_accounts";
    public static final String ACC_GROUPS = "acc.account_groups";
    public static final String ACC_SUBGROUPS = "acc.account_subgroups";
    public static final String ACC_PERIODS = "acc.periods";
    public static final String ACC_FISCAL_YEARS = "acc.fiscal_years";
    public static final String ACC_LEDGER_ADJUSTMENTS = "acc.ledger_adjustments";
    public static final String ACC_TRIAL_BALANCE = "acc.trial_balance";
    public static final String ACC_LEDGER_STATEMENT = "acc.ledger_statement";
    public static final String ACC_RRN_POSTINGS = "acc.rrn_postings";
    public static final String ACC_MERCHANT_LEDGER = "acc.merchant_ledger";

    private static final Map<String, DatasetSpec> ALL = new LinkedHashMap<>();

    static {
        // Global Search. txnfromDate/txntoDate observed in the search drawer object.
        add(new DatasetSpec(CS_TRANSACTIONS, "C&S transactions (Global Search)", CANDS, "/api/cands/transaction/search",
                LIST_ENVELOPE, Map.of("requestType", "ALL"), null, null, "txnfromDate", "txntoDate",
                List.of("id"), "#/cands/global-search", false));
        add(new DatasetSpec(CS_ROLLING_RESERVE, "Rolling reserve transactions", CANDS, "/api/cands/transaction/search",
                PAGED_FLAT, Map.of("requestType", "OTHERS", "isRollingFlag", true), "salesDateTime", "DESC",
                null, null, List.of("id"), "#/cands/rolling-reserve", false));
        add(new DatasetSpec(CS_INSTANT_EXCEPTIONS, "Move-to-instant-settlement exception queue", CANDS,
                "/api/cands/transaction/search", PAGED_FLAT,
                Map.of("requestType", "CARDS", "instantFeeCalculationFailed", true), null, null, null, null,
                List.of("id"), "#/cands/instant-settlement", false));
        add(files(CS_SCHEME_FILES, "Scheme files", "FILES", "#/cands/files"));
        add(files(CS_PAYMENT_FILES, "Payment files", "PAYMENT_FILES", "#/cands/payment-files"));
        add(files(CS_REPORTS, "C&S reports", "REPORTS", "#/cands/reports"));
        add(new DatasetSpec(CS_PROCESSED_FILES, "Processed incoming files", CANDS, "/api/clearing/getallprocessedfiles",
                LIST_ENVELOPE, Map.of("entryType", "PROCESSED_FILES_INCOMING"), null, null, null, null,
                List.of("fileName", "processedDateTime"), "#/cands/processed", false));
        add(new DatasetSpec(CS_SCHEME_REJECTS, "Scheme reject transactions", CANDS, "/api/txn/getrejtxndetails",
                LIST_ENVELOPE, Map.of("entryType", "reject_transactions"), null, null, "txnfromDate", "txntoDate",
                List.of("id"), "#/cands/incoming-reject-txns", false));
        add(new DatasetSpec(CS_PAYMENTS_CREDIT, "Merchant payments (credit)", CANDS, "/api/merPayReq/getAllMerPay",
                LIST_ENVELOPE, Map.of("recordIdentifier", "CREDIT"), null, null, null, null,
                List.of("id"), "#/cands/payment", false));
        add(new DatasetSpec(CS_PAYMENTS_DEBIT, "Merchant payments (debit)", CANDS, "/api/merPayReq/getAllMerPay",
                LIST_ENVELOPE, Map.of("recordIdentifier", "DEBIT"), null, null, null, null,
                List.of("id"), "#/cands/payment", false));
        // Per payment: {runNumber, merchantNumber, dbOrcrToMerchant}. Stored as one snapshot per payment key.
        add(new DatasetSpec(CS_PAYMENT_TXNS, "Transactions behind a payment", CANDS, "/api/merPayReq/getTxns",
                LIST_ENVELOPE, Map.of(), null, null, null, null, List.of(), "#/cands/payment-details", true));
        add(new DatasetSpec(CS_JV, "Journal vouchers", CANDS, "/api/clearing/getalljv",
                LIST_ENVELOPE, Map.of("entryType", "INC_NETAMOUNT_JV"), null, null, null, null,
                List.of("incomingJvName", "incomingFileName", "date"), "#/cands/journal-voucher", false));
        add(new DatasetSpec(CS_BULK_DOWNLOAD, "Bulk download jobs", CANDS, "/api/bulkdownload/getall",
                PAGED_FLAT, Map.of(), "triggerDateTime", "DESC", null, null, List.of("id"),
                "#/cands/bulk-download", false));
        add(new DatasetSpec(CS_UPI_SUMMARY_BDF, "UPI summary BDF", CANDS, "/api/upi-summary-bdf/getall",
                UPI_SEARCH, Map.of(), null, null, null, null, List.of("fileName"), "#/cands/upi-summary-bdf", false));
        add(new DatasetSpec(CS_PAYMENT_EXCEPTIONS, "Payment exceptions", CANDS, "/api/payExp/getall",
                PAGED_FLAT, Map.of(), "text2", "ASC", null, null, List.of("id"), "CandS screen to confirm (payExp)", false));

        add(ref(ACC_TXN_TYPES, "Transaction types", "/api/transactiontype/getall", "transactionCode",
                List.of("transactionCode"), "#/configurations/transactiontype-getall"));
        add(ref(ACC_LEDGERS, "Chart of accounts", "/api/ledgeraccount/getall", "text2",
                List.of("id"), "#/configurations/chartofAccounts-getall"));
        add(ref(ACC_GROUPS, "Account groups", "/api/group/getall", "groupCode",
                List.of("groupCode"), "#/configurations/accounting-getall"));
        add(ref(ACC_SUBGROUPS, "Account sub-groups", "/api/subgroup/getall", "groupCode",
                List.of("groupCode", "subGroupCode"), "#/configurations/accounting-sub-group-getall"));
        add(ref(ACC_PERIODS, "Accounting periods", "/api/fiscalperiod/getall", "periodName",
                List.of("periodName"), "#/configurations/period"));
        add(ref(ACC_FISCAL_YEARS, "Fiscal years", "/api/fiscalyear/getall", "fiscalYearCode",
                List.of("fiscalYearCode"), "#/configurations/fiscal-year"));
        add(ref(ACC_LEDGER_ADJUSTMENTS, "Ledger adjustments", "/api/ledger-adjustment/getall", "text2",
                List.of("id"), "#/configurations/ledger-adjustment"));
        add(new DatasetSpec(ACC_TRIAL_BALANCE, "Trial balance", ACCOUNTING, "/api/accounting/trialbalance-getall",
                DOWNLOAD_LIST, Map.of(), "accountGroup", "ASC", "fromDate", "toDate", List.of(),
                "#/configurations/trail-balance", true));
        add(new DatasetSpec(ACC_LEDGER_STATEMENT, "Accounting-Entry ledger statement", ACCOUNTING,
                "/api/accounting/getDetailsEntry", LIST_ENVELOPE, Map.of(), null, null, "fromDate", "toDate",
                List.of(), "#/configurations/accounting-entry", true));
        add(new DatasetSpec(ACC_RRN_POSTINGS, "Accounting postings by RRN", ACCOUNTING,
                "/api/accounting/searchAllEntries", LIST_ENVELOPE, Map.of(), null, null, null, null,
                List.of(), "#/configurations/accounting-entry (RRN search)", true));
        add(new DatasetSpec(ACC_MERCHANT_LEDGER, "Merchant-Entry ledger", ACCOUNTING, "/api/merchant-ledger/getall",
                LIST_ENVELOPE, Map.of(), null, null, "fromDate", "toDate", List.of(),
                "#/configurations/merchant-entry", true));
    }

    private static DatasetSpec files(String key, String label, String flag, String screen) {
        return new DatasetSpec(key, label, CANDS, "/api/clearing/getallfiles", LIST_ENVELOPE,
                Map.of("entryType", "FILE_RUN_INFO", "file_flag", flag, "platFormType", "ACQURING"),
                null, null, "txnfromDate", "txntoDate", List.of("fileName", "localTxnDate", "fileFlag"), screen, false);
    }

    private static DatasetSpec ref(String key, String label, String path, String sort, List<String> nk, String screen) {
        return new DatasetSpec(key, label, ACCOUNTING, path, PAGED_FLAT, Map.of(), sort, "ASC", null, null, nk,
                screen, false);
    }

    private static void add(DatasetSpec s) {
        ReadOnlyGuard.check(s.path()); // fail fast at class load if a non-read path ever slips in
        ALL.put(s.key(), s);
    }

    public static Optional<DatasetSpec> find(String key) {
        return Optional.ofNullable(ALL.get(key));
    }

    public static DatasetSpec get(String key) {
        return find(key).orElseThrow(() -> new IllegalArgumentException("Unknown dataset " + key));
    }

    public static List<DatasetSpec> all() {
        return List.copyOf(ALL.values());
    }

    private Datasets() {
    }
}
