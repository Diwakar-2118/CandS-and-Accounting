package in.toucan.monitor.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/** Pages through a dataset and returns provenanced records. Never throws for source failures. */
public class DatasetCollector {

    private static final Logger log = LoggerFactory.getLogger(DatasetCollector.class);

    private final ToucanApiClient client;
    private final Clock clock;
    private final ObjectMapper canonical;

    public DatasetCollector(ToucanApiClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
        this.canonical = client.mapper().copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /** Collect a dataset with optional date window and extra requestObject filters. */
    public CollectionResult collect(DatasetSpec spec, LocalDate from, LocalDate to, Map<String, Object> extra) {
        Map<String, Object> ro = new LinkedHashMap<>(spec.baseRequestObject());
        if (extra != null) ro.putAll(extra);
        if (spec.dateFilterable() && from != null && to != null) {
            ro.put(spec.fromField(), from.toString());
            ro.put(spec.toField(), to.toString());
        }
        String params = toJson(new TreeMap<>(ro));
        Instant retrievedAt = clock.instant();
        int pageSize = client.config().pageSize();
        int maxPages = client.config().maxPages();

        List<JsonNode> rows = new ArrayList<>();
        Long total = null;
        boolean truncated = false;
        JsonNode firstBody = null;
        try {
            for (int page = 0; ; page++) {
                if (page >= maxPages) {
                    truncated = true;
                    break;
                }
                ObjectNode body = client.body(spec.style(), spec.path(), ro, page, pageSize, spec.sortField(),
                        spec.sortOrder());
                ApiEnvelope env = client.post(spec.system(), spec.path(), body);
                if (!env.isOk()) {
                    // Some screens answer an empty search with success:false + "No records found".
                    // That is a valid empty result; anything else (e.g. NOTUSED/500) means unavailable.
                    if (env.httpStatus() / 100 == 2 && isNoRecords(env.message())) break;
                    return failed(spec, params, "Source error: " + env.describeError(), retrievedAt);
                }
                if (firstBody == null) firstBody = env.body();
                OptionalLong t = env.total();
                if (t.isPresent()) total = t.getAsLong();
                List<JsonNode> items = env.items();
                rows.addAll(items);
                if (items.size() < pageSize) break;
                if (total != null && rows.size() >= total) break;
            }
        } catch (ReadOnlyGuard.WriteAttemptBlockedException e) {
            throw e; // programming error, never swallow
        } catch (TokenProvider.AuthException e) {
            return failed(spec, params, "Authentication failed: " + e.getMessage(), retrievedAt);
        } catch (RuntimeException e) {
            log.warn("Collection of {} failed: {}", spec.key(), e.toString());
            return failed(spec, params, e.getClass().getSimpleName() + ": " + e.getMessage(), retrievedAt);
        }
        if (truncated && total != null && rows.size() >= total) truncated = false;

        List<CollectedRecord> records = new ArrayList<>();
        if (spec.snapshot()) {
            ObjectNode payload = client.mapper().createObjectNode();
            payload.set("query", client.mapper().valueToTree(new TreeMap<>(ro)));
            ArrayNode arr = payload.putArray("rows");
            rows.forEach(arr::add);
            if (firstBody != null && firstBody.has("netZero")) payload.set("netZero", firstBody.get("netZero"));
            if (total != null) payload.put("totalReported", total);
            records.add(new CollectedRecord(spec.key(), spec.system(), spec.path(), params, "query:" + params,
                    hash(payload), payload, retrievedAt));
        } else {
            for (JsonNode row : rows) {
                String h = hash(row);
                records.add(new CollectedRecord(spec.key(), spec.system(), spec.path(), params,
                        naturalKey(spec, row, h), h, row, retrievedAt));
            }
        }
        if (truncated) {
            log.warn("{}: stopped at maxPages={} with {} of {} rows", spec.key(), maxPages, rows.size(), total);
        }
        return new CollectionResult(spec.key(), spec.path(), params, true, null, records, total, truncated, retrievedAt);
    }

    static boolean isNoRecords(String message) {
        return message != null && message.toLowerCase(java.util.Locale.ROOT).matches(".*\\bno (records?|data)( found)?\\b.*");
    }

    static String naturalKey(DatasetSpec spec, JsonNode row, String hash) {
        if (spec.naturalKey().isEmpty()) return "hash:" + hash;
        StringBuilder sb = new StringBuilder();
        for (String f : spec.naturalKey()) {
            JsonNode v = row.path(f);
            if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) return "hash:" + hash;
            if (!sb.isEmpty()) sb.append('|');
            sb.append(v.isValueNode() ? v.asText() : v.toString());
        }
        return sb.toString();
    }

    public String hash(JsonNode node) {
        try {
            Object plain = canonical.treeToValue(node, Object.class);
            byte[] bytes = canonical.writeValueAsString(plain).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object o) {
        try {
            return canonical.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static CollectionResult failed(DatasetSpec spec, String params, String error, Instant at) {
        return new CollectionResult(spec.key(), spec.path(), params, false, error, List.of(), null, false, at);
    }
}
