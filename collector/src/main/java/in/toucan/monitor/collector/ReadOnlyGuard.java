package in.toucan.monitor.collector;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hard read-only enforcement. Toucan's read APIs are POSTs (search/getall), so the HTTP method
 * says nothing about safety. Instead every path must be on an explicit allowlist, AND must not
 * contain any write-like verb. Both checks run on every call; the guard cannot be disabled.
 *
 * <p>The allowlist is the set of read endpoints confirmed in Phase 1 (see docs/phase1-discovery.md).
 * General Config endpoints are deliberately absent: they expose a GCP service-account key.
 */
public final class ReadOnlyGuard {

    /** Authentication calls. They create a session but change no business data. */
    static final Set<String> AUTH_PATHS = Set.of(
            "/api/uam/auth/login",
            "/api/uam/auth/refreshToken");

    static final Set<String> READ_PATHS = Set.of(
            // CandS
            "/api/cands/transaction/search",
            "/api/cands/transaction/getall",
            "/api/clearing/getallfiles",
            "/api/clearing/getallprocessedfiles",
            "/api/clearing/getbyfilename",
            "/api/clearing/getalljv",
            "/api/txn/getrejtxndetails",
            "/api/merPayReq/getAllMerPay",
            "/api/merPayReq/getTxns",
            "/api/bulkdownload/getall",
            "/api/upi-summary-bdf/getall",
            "/api/payExp/getall",
            "/api/schemeconfig/getall",
            "/api/trace-logs/getall",
            // Accounting
            "/api/transactiontype/getall",
            "/api/ledgeraccount/getall",
            "/api/group/getall",
            "/api/subgroup/getall",
            "/api/fiscalperiod/getall",
            "/api/fiscalyear/getall",
            "/api/ledger-adjustment/getall",
            "/api/accounting/trialbalance-getall",
            "/api/accounting/getDetailsEntry",
            "/api/accounting/searchAllEntries",
            "/api/merchant-ledger/getall");

    /** GET downloads: /api/clearing/reports/download/{yyyy-MM-dd}/{fileName}/{fileFlag}. */
    static final List<Pattern> READ_PATTERNS = List.of(
            Pattern.compile("^/api/clearing/reports/download/\\d{4}-\\d{2}-\\d{2}/[^/]+/[A-Z_]+$"));

    /** Defence in depth: even an allowlisted path is refused if it carries one of these verbs. */
    private static final Pattern WRITE_VERBS = Pattern.compile(
            "(create|update|upd\\b|/upd$|delete|trigger|submit|hold|release|retry|upload|/payreq|repost|"
                    + "approve|reject(?!txn)|closure|reopen|notify|flag|makerupdate|checkerupdate|"
                    + "generalconfig|aesmasterkey|/mail|accept|logout|resetpassword|changepassword)",
            Pattern.CASE_INSENSITIVE);

    private ReadOnlyGuard() {
    }

    /** @throws WriteAttemptBlockedException if the path is not a known read endpoint. */
    public static void check(String path) {
        String p = normalise(path);
        if (AUTH_PATHS.contains(p)) {
            return;
        }
        boolean exact = READ_PATHS.contains(p);
        boolean templated = !exact && READ_PATTERNS.stream().anyMatch(r -> r.matcher(p).matches());
        if (!exact && !templated) {
            throw new WriteAttemptBlockedException("Blocked: " + p + " is not an allowlisted read endpoint");
        }
        // For templated downloads only the fixed prefix is scanned: report names such as
        // MERCHANT_PAYMENT_HOLD legitimately contain verbs.
        String lower = (templated ? "/api/clearing/reports/download/" : p).toLowerCase(Locale.ROOT);
        // getrejtxndetails is a read ("get" prefix); strip it before the verb scan.
        String scanned = lower.replace("getrejtxndetails", "");
        if (WRITE_VERBS.matcher(scanned).find()) {
            throw new WriteAttemptBlockedException("Blocked: " + p + " contains a write verb");
        }
    }

    public static boolean isAllowed(String path) {
        try {
            check(path);
            return true;
        } catch (WriteAttemptBlockedException e) {
            return false;
        }
    }

    static String normalise(String path) {
        if (path == null) throw new WriteAttemptBlockedException("Blocked: null path");
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith("/")) p = "/" + p;
        if (p.contains("..")) throw new WriteAttemptBlockedException("Blocked: path traversal in " + p);
        return p;
    }

    public static final class WriteAttemptBlockedException extends RuntimeException {
        public WriteAttemptBlockedException(String message) {
            super(message);
        }
    }
}
