package in.toucan.monitor.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Engine value types. Grouped in one file to keep the model easy to read end to end. */
public final class Model {

    private Model() {
    }

    public enum Outcome {
        PASS, FAIL, WARN,
        /** Accounting not yet posted but still inside its SLA. */
        PENDING,
        /** Accounting not posted after its SLA, or an expected file absent after its due time. */
        MISSING,
        /** Could not be judged: source unavailable, field not confirmed, mapping not confirmed, config absent. */
        NOT_EVALUATED
    }

    /** Where a fact came from. Every KPI and failed check links back to one or more of these. */
    public record SourceRef(String system, String dataset, String endpoint, String screen, String recordKey,
                            String requestParams, Instant retrievedAt) {
    }

    public record Evidence(String expected, String actual, List<SourceRef> sources, Map<String, Object> details) {
        public static Evidence of(String expected, String actual, List<SourceRef> sources) {
            return new Evidence(expected, actual, sources, Map.of());
        }
    }

    /**
     * One rule verdict. {@code subjectKey} is the record or group judged; "SUMMARY" rows carry the
     * per-rule counts (evaluated/passed/failed) for the Runs screen.
     */
    public record RuleResult(String ruleId, String ruleName, String category, String subjectKey, Outcome outcome,
                             String message, String processor, LocalDate businessDate, String ttCode, String ledger,
                             Evidence evidence) {
    }

    /** A stored source record with its provenance. */
    public record Rec(SourceRef ref, JsonNode payload) {
    }

    /** Availability of a dataset for this evaluation. */
    public record DatasetState(boolean available, String error, Instant lastSuccessAt, boolean truncated) {
        public static DatasetState unavailable(String why) {
            return new DatasetState(false, why, null, false);
        }
    }

    public enum Side { DR, CR }

    public record GlLine(String account, Side side) {
    }

    public enum MappingStatus { CONFIRMED, UNCONFIRMED }

    /** A Transaction Type as configured in Accounting, plus the business confirmation state. */
    public record TtMapping(String code, String name, List<GlLine> lines, MappingStatus status, SourceRef source) {
    }

    /** Expected-file calendar entry supplied by the business. Nothing here is defaulted. */
    public record ExpectedFile(String dataset, String fileNameRegex, List<java.time.DayOfWeek> days,
                               java.time.LocalTime dueBy) {
    }

    /** Aggregates from an external PayU recon file, per business date. */
    public record ReconTotals(LocalDate date, long count, java.math.BigDecimal totalAmount,
                              java.math.BigDecimal processingFees, java.math.BigDecimal serviceTax,
                              java.math.BigDecimal settlementAmount, java.math.BigDecimal netAmount, SourceRef source) {
    }
}
