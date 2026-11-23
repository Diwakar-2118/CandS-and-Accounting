package in.toucan.monitor.engine;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Everything the rules need that is business- or environment-defined. Defaults contain ONLY
 * field names observed in Phase 1 (UI column bindings and request objects). Fields that were not
 * observed (fee breakdown, payment amount) default to empty, so the dependent rules report
 * NOT_EVALUATED until someone confirms the name from the runtime discovery output.
 *
 * @param fields              logical field -> candidate JSON paths
 * @param tolerance           money comparison tolerance (INR)
 * @param jvWindowHours       hours after the JV date before a not-completed JV is a failure; null = not supplied
 * @param defaultSlaHours     accounting posting SLA when no per-key SLA exists; null = not supplied
 * @param slaHoursByKey       SLA per C&S transactionCode or TT code
 * @param fileSuccessPatterns statuses that count as success for files
 * @param calendar            expected files (empty = not supplied)
 * @param payuPlatformValues  platformType values that identify PayU (observed: PAYU)
 */
public record EngineConfig(
        Map<String, List<String>> fields,
        BigDecimal tolerance,
        Integer jvWindowHours,
        Integer defaultSlaHours,
        Map<String, Integer> slaHoursByKey,
        List<Pattern> fileSuccessPatterns,
        List<Model.ExpectedFile> calendar,
        List<String> payuPlatformValues) {

    public static Map<String, List<String>> observedFieldDefaults() {
        Map<String, List<String>> f = new HashMap<>();
        // C&S transaction (Global Search columns + search/payment request objects)
        f.put("txn.id", List.of("id"));
        f.put("txn.rrn", List.of("rrn"));
        f.put("txn.arn", List.of("arn"));
        f.put("txn.invoice", List.of("invoiceNumber"));
        f.put("txn.mid", List.of("merchantNumber"));
        f.put("txn.merchantName", List.of("merchantName"));
        f.put("txn.terminal", List.of("terminalNumber"));
        f.put("txn.runNumber", List.of("runNumber"));
        f.put("txn.inputSourceId", List.of("inputSourceId"));
        f.put("txn.amount", List.of("txnAmount.value", "txnAmount"));
        f.put("txn.currency", List.of("txnAmount.code"));
        f.put("txn.netAmount", List.of("netAmount.value", "netAmount"));
        f.put("txn.salesDateTime", List.of("salesDateTime"));
        f.put("txn.scheme", List.of("schemeCode"));
        f.put("txn.platform", List.of("platformType"));
        f.put("txn.transactionCode", List.of("transactionCode"));
        f.put("txn.settlementStatus", List.of("settlementStatus"));
        f.put("txn.lifeCycleStatus", List.of("lifeCycleStatus"));
        f.put("txn.transactionStatus", List.of("transactionStatus"));
        f.put("txn.txnStatus", List.of("txnStatus"));
        f.put("txn.paymentReleaseStatus", List.of("paymentReleaseStatusEnum"));
        f.put("txn.dbOrCr", List.of("dbOrcrToMerchant"));
        // Fee breakdown: seen on the detail page, field names NOT captured -> confirm from discovery.
        for (String k : List.of("txn.mdrAmount", "txn.mdrCgst", "txn.mdrSgst", "txn.feeAmount", "txn.feeCgst",
                "txn.feeSgst", "txn.paymentAmount")) {
            f.put(k, List.of());
        }
        // Payments: runNumber + merchantNumber are sent to getTxns from the payment record (observed).
        f.put("payment.id", List.of("id"));
        f.put("payment.runNumber", List.of("runNumber"));
        f.put("payment.mid", List.of("merchantNumber"));
        f.put("payment.amount", List.of()); // not observed
        f.put("payment.releaseStatus", List.of()); // not observed
        // Files / reports (FilesTableDataSourceColumns etc.)
        f.put("file.name", List.of("fileName"));
        f.put("file.status", List.of("fileStatus", "processingStatus", "status"));
        f.put("file.flag", List.of("fileFlag"));
        f.put("file.date", List.of("localTxnDate", "processedDateTime", "triggerDate"));
        f.put("file.time", List.of("fileTime", "processedTime"));
        // JV (JVTableDataSourceColumns)
        f.put("jv.name", List.of("incomingJvName"));
        f.put("jv.file", List.of("incomingFileName"));
        f.put("jv.date", List.of("date"));
        f.put("jv.scheme", List.of("schemeCode"));
        f.put("jv.status", List.of("fileStatus"));
        f.put("jv.flag", List.of("fileFlag"));
        // UPI summary BDF
        f.put("bdf.file", List.of("fileName"));
        f.put("bdf.date", List.of("fileDate"));
        f.put("bdf.generated", List.of("isBdfGenerated"));
        // Accounting transaction types (transaction-type component)
        f.put("tt.code", List.of("transactionCode"));
        f.put("tt.name", List.of("transactionName"));
        f.put("tt.lines", List.of("accountDetails"));
        f.put("tt.line.account", List.of("accountName"));
        f.put("tt.line.side", List.of("creditOrDebit"));
        // Accounting postings by RRN. Runtime discovery (23 Nov 2026): rows carry id, description (ledger),
        // transactionName (transaction-type NAME), creditOrDebit, debitAmount/creditAmount {value, code}.
        // The UI column labels (subModule, ledgerName) are kept as fallbacks.
        f.put("posting.id", List.of("id"));
        f.put("posting.ledger", List.of("description", "ledgerName"));
        f.put("posting.side", List.of("creditOrDebit"));
        f.put("posting.ttName", List.of("transactionName"));
        f.put("posting.debit", List.of("debitAmount"));
        f.put("posting.credit", List.of("creditAmount"));
        f.put("posting.subModule", List.of("transactionName", "subModule"));
        f.put("posting.dateTime", List.of("transactionDateTime", "dateTime"));
        f.put("posting.ttCode", List.of()); // postings carry the TT name, not a code; R09 matches by name
        // Trial balance (updateDisplayedColumns)
        f.put("tb.debit", List.of("debit"));
        f.put("tb.credit", List.of("credit"));
        f.put("tb.name", List.of("accountGroup", "subGroup", "accountName"));
        // Ledger adjustment (detail txnArray)
        f.put("adj.lines", List.of("txnArray", "entries"));
        f.put("adj.debit", List.of("debitAmount"));
        f.put("adj.credit", List.of("creditAmount"));
        f.put("adj.client", List.of("merchantNumber"));
        f.put("adj.ref", List.of("rrn"));
        // Periods / fiscal years
        f.put("period.name", List.of("periodName"));
        f.put("period.status", List.of("status"));
        f.put("period.start", List.of("startDate"));
        f.put("period.end", List.of("endDate"));
        f.put("fy.code", List.of("fiscalYearCode"));
        f.put("fy.status", List.of("status"));
        f.put("fy.start", List.of("startDate"));
        f.put("fy.end", List.of("endDate"));
        // Accounting-Entry ledger statement
        f.put("stmt.date", List.of("accountingDate"));
        f.put("stmt.desc", List.of("descriptionType"));
        f.put("stmt.debitCount", List.of("debitCount"));
        f.put("stmt.debitAmount", List.of("debitAmount"));
        f.put("stmt.creditCount", List.of("creditCount"));
        f.put("stmt.creditAmount", List.of("creditAmount"));
        f.put("stmt.balance", List.of("balance"));
        return f;
    }

    public static EngineConfig defaults() {
        return new EngineConfig(observedFieldDefaults(), new BigDecimal("0.01"), null, null, Map.of(),
                List.of(Pattern.compile("(?i).*SUCCESSFULLY.*"), Pattern.compile("(?i)successfully processed"),
                        Pattern.compile("(?i)^completed$")),
                List.of(), List.of("PAYU"));
    }

    public Fields f() {
        return new Fields(fields);
    }

    public boolean isFileSuccess(String status) {
        return status != null && fileSuccessPatterns.stream().anyMatch(p -> p.matcher(status).matches());
    }

    public Integer slaHoursFor(String key) {
        if (key != null && slaHoursByKey.containsKey(key)) return slaHoursByKey.get(key);
        return defaultSlaHours;
    }
}
