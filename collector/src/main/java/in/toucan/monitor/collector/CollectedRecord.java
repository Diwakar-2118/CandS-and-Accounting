package in.toucan.monitor.collector;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One record pulled from a source, with full provenance: which system and endpoint, with which
 * request parameters, when, and under which natural key / content hash (for idempotent upserts).
 */
public record CollectedRecord(
        String dataset,
        ToucanSystem system,
        String endpoint,
        String requestParams,
        String naturalKey,
        String contentHash,
        JsonNode payload,
        Instant retrievedAt) {
}
