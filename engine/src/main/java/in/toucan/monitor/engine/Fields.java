package in.toucan.monitor.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads logical fields from raw source JSON using the configured candidate paths
 * ({@link EngineConfig#fields()}). A logical field with no configured path is "not confirmed";
 * rules that depend on it report NOT_EVALUATED instead of guessing.
 */
public final class Fields {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy"));
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));

    private final Map<String, List<String>> paths;

    public Fields(Map<String, List<String>> paths) {
        this.paths = paths;
    }

    public boolean configured(String logical) {
        List<String> p = paths.get(logical);
        return p != null && !p.isEmpty();
    }

    public JsonNode node(JsonNode n, String logical) {
        List<String> candidates = paths.getOrDefault(logical, List.of());
        for (String c : candidates) {
            JsonNode v = at(n, c);
            if (!v.isMissingNode() && !v.isNull()) return v;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    public String text(JsonNode n, String logical) {
        JsonNode v = node(n, logical);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.isValueNode() ? v.asText() : v.toString();
        return s.isBlank() ? null : s.trim();
    }

    public Optional<BigDecimal> decimal(JsonNode n, String logical) {
        return toDecimal(node(n, logical));
    }

    public Optional<LocalDate> date(JsonNode n, String logical) {
        return parseDate(text(n, logical));
    }

    /** Dot path with optional array index: "txnAmount.value", "rows[0].x". */
    public static JsonNode at(JsonNode n, String dotPath) {
        JsonNode cur = n;
        for (String part : dotPath.split("\\.")) {
            if (cur == null) break;
            int b = part.indexOf('[');
            if (b >= 0) {
                cur = cur.path(part.substring(0, b));
                int idx = Integer.parseInt(part.substring(b + 1, part.indexOf(']')));
                cur = cur.path(idx);
            } else {
                cur = cur.path(part);
            }
        }
        return cur;
    }

    /** Numbers, numeric strings with commas, "{value, code}" money objects, and "1,234.00 DR" balances. */
    public static Optional<BigDecimal> toDecimal(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return Optional.empty();
        if (v.isObject() && v.has("value")) return toDecimal(v.get("value"));
        if (v.isNumber()) return Optional.of(v.decimalValue());
        if (v.isTextual()) return parseAmount(v.asText());
        return Optional.empty();
    }

    public static Optional<BigDecimal> parseAmount(String s) {
        if (s == null) return Optional.empty();
        String t = s.replace(",", "").replace("₹", "").replaceAll("(?i)\\b(INR|DR|CR)\\b", "").trim();
        if (t.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new BigDecimal(t));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Signed balance: DR positive, CR negative. A bare number keeps its sign. */
    public static Optional<BigDecimal> signedBalance(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) return Optional.empty();
        if (v.isNumber()) return Optional.of(v.decimalValue());
        String s = v.asText();
        Optional<BigDecimal> amt = parseAmount(s);
        if (amt.isEmpty()) return amt;
        if (s.toUpperCase().contains("CR")) return Optional.of(amt.get().abs().negate());
        if (s.toUpperCase().contains("DR")) return Optional.of(amt.get().abs());
        return amt;
    }

    public static Optional<LocalDate> parseDate(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        String t = s.trim();
        if (t.matches("^\\d{12,13}$")) {
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(t)).atZone(IST).toLocalDate());
        }
        try {
            return Optional.of(OffsetDateTime.parse(t).atZoneSameInstant(IST).toLocalDate());
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        for (DateTimeFormatter f : DATE_TIME_FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(t, f).toLocalDate());
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        String datePart = t.length() > 10 && (t.charAt(10) == 'T' || t.charAt(10) == ' ') ? t.substring(0, 10) : t;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(datePart, f));
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        return Optional.empty();
    }

    public static Optional<Instant> parseInstant(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        String t = s.trim();
        if (t.matches("^\\d{12,13}$")) return Optional.of(Instant.ofEpochMilli(Long.parseLong(t)));
        try {
            return Optional.of(OffsetDateTime.parse(t).toInstant());
        } catch (DateTimeParseException ignored) {
            // next
        }
        for (DateTimeFormatter f : DATE_TIME_FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(t, f).atZone(IST).toInstant());
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        return parseDate(t).map(d -> d.atStartOfDay(IST).toInstant());
    }

    public static ZoneId zone() {
        return IST;
    }
}
