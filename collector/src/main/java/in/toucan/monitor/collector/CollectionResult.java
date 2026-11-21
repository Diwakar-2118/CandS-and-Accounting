package in.toucan.monitor.collector;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of collecting one dataset query. {@code ok=false} means the source is unavailable and
 * every KPI built on it must show "Not available" (never 0).
 *
 * @param truncated true when maxPages stopped paging before the server's reported total
 */
public record CollectionResult(
        String dataset,
        String endpoint,
        String requestParams,
        boolean ok,
        String error,
        List<CollectedRecord> records,
        Long totalReported,
        boolean truncated,
        Instant retrievedAt) {
}
