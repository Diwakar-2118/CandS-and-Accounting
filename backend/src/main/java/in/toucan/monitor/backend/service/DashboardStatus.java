package in.toucan.monitor.backend.service;

import java.util.Locale;

/**
 * Maps raw file/report statuses onto the dashboard vocabulary
 * (Generated, Received, Uploaded, Processing, Processed, Failed, Rejected). "Missing" is never derived
 * from a status: it only comes from the expected-file calendar (rule R05). Anything not recognised is
 * shown as "Unmapped" and the raw value always stays visible next to it.
 */
public final class DashboardStatus {

    private DashboardStatus() {
    }

    public static String of(String dataset, String raw) {
        if (raw == null || raw.isBlank()) return "Unmapped";
        String s = raw.toUpperCase(Locale.ROOT);
        if (dataset != null && dataset.endsWith("upi_summary_bdf")) {
            return s.equals("TRUE") ? "Generated" : "Unmapped";
        }
        if (s.contains("FAIL") || s.contains("ERROR")) return "Failed";
        if (s.contains("REJECT")) return "Rejected";
        if (s.contains("GENERATED")) return "Generated";
        if (s.contains("PROCESSING") || s.contains("IN_PROGRESS") || s.contains("IN PROGRESS")) return "Processing";
        if (s.contains("PROCESSED")) return "Processed";
        if (s.contains("RECEIVED")) return "Received";
        if (s.contains("UPLOAD")) return "Uploaded";
        if (s.equals("COMPLETED")) return "Processed";
        return "Unmapped";
    }
}
