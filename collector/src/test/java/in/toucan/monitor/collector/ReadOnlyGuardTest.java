package in.toucan.monitor.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyGuardTest {

    /** Write/action endpoints observed in the CandS and Accounting bundles. All must be refused. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/cands/transaction/instantretry",   // Move to Instant Settlement
            "/api/rollingreserve/txnhold",           // Mark payment as Hold
            "/api/rollingreserve/updatetxn",
            "/api/txn/holdrelease",
            "/api/feescrn/trigger",                  // Exception/Outgoing trigger
            "/api/feescrn/manualoutgoingtrigger",
            "/api/stubdata/update",                  // Stub Data submit
            "/api/ird/create",                       // Create IRD
            "/api/schemeconfig/update",              // Clearing config
            "/api/generalconfig/getall",             // exposes GCP key: never ingested
            "/api/generalconfig/update",
            "/api/journalvoucher/jvtrigger",
            "/api/journalvoucher/upd",
            "/api/ledger-adjustment/generic-api",    // Ledger Adjustment Add New
            "/api/fiscalperiod/update",
            "/api/fiscalyear/create",
            "/api/group/update-status",              // Active toggles
            "/api/ledgeraccount/update-status",
            "/api/transactiontype/create",
            "/api/upi-summary-bdf/flag",
            "/api/ftxpaymentreqres/payreq",
            "/api/txn/rejectrepost",
            "/api/cands/transaction/download",       // generates a server-side export; not needed
            "/api/aesMasterkey/getkeys",
            "/api/unknown/getall"
    })
    void blocksWritesAndUnknownPaths(String path) {
        assertThatThrownBy(() -> ReadOnlyGuard.check(path))
                .isInstanceOf(ReadOnlyGuard.WriteAttemptBlockedException.class);
    }

    @Test
    void allowsEveryCataloguedDataset() {
        Datasets.all().forEach(d -> assertThat(ReadOnlyGuard.isAllowed(d.path())).as(d.key()).isTrue());
    }

    @Test
    void allowsReportDownloadEvenWhenFileNameContainsAVerb() {
        assertThat(ReadOnlyGuard.isAllowed(
                "/api/clearing/reports/download/2026-11-19/MERCHANT_PAYMENT_HOLD_20261119.csv/REPORTS")).isTrue();
    }

    @Test
    void blocksTraversalAndQueryTricks() {
        assertThat(ReadOnlyGuard.isAllowed("/api/clearing/getallfiles/../../generalconfig/update")).isFalse();
        assertThat(ReadOnlyGuard.isAllowed("/api/clearing/getallfiles?x=1")).isTrue();
    }
}
