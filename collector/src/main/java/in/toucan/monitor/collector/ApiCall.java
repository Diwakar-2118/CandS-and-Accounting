package in.toucan.monitor.collector;

import java.time.Instant;

/**
 * One outbound HTTP call, recorded for the run's network log. Acceptance requires showing that a
 * full run made no write calls; this log (persisted per run) is that evidence. Bodies and tokens
 * are never recorded.
 */
public record ApiCall(
        Instant at,
        ToucanSystem system,
        String method,
        String path,
        int httpStatus,
        Boolean envelopeSuccess,
        Integer envelopeStatusCode,
        long durationMs,
        boolean blocked,
        String error) {
}
