package in.toucan.monitor.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Toucan response envelope: {@code {success, message, statusCode, totalCount, downloadCSV, responseObject}}.
 *
 * <p>Errors arrive as HTTP 200 with {@code success:false, statusCode:500, message:"NOTUSED"}, so
 * success is decided from the body, never from the HTTP status alone.
 *
 * <p>List payloads come in three observed shapes: {@code responseObject} is an array
 * (transaction search), {@code responseObject.content} is a Spring page (files, JV, payments),
 * or {@code object.content} (merPayReq/getTxns via the search-object style).
 */
public record ApiEnvelope(int httpStatus, JsonNode body) {

    public boolean success() {
        return body != null && body.path("success").asBoolean(false);
    }

    public Integer statusCode() {
        JsonNode sc = body == null ? MissingNode.getInstance() : body.path("statusCode");
        return sc.isNumber() || sc.isTextual() && sc.asText().matches("\\d+") ? sc.asInt() : null;
    }

    public String message() {
        return body == null ? null : body.path("message").asText(null);
    }

    /** True only when HTTP is 2xx, success is true and any statusCode in the body is 2xx. */
    public boolean isOk() {
        if (httpStatus < 200 || httpStatus >= 300) return false;
        if (!success()) return false;
        Integer sc = statusCode();
        return sc == null || (sc >= 200 && sc < 300);
    }

    /** True for the HTTP-200-but-failed pattern (e.g. message NOTUSED). */
    public boolean isSoftError() {
        return httpStatus >= 200 && httpStatus < 300 && !isOk();
    }

    public List<JsonNode> items() {
        List<JsonNode> out = new ArrayList<>();
        if (body == null) return out;
        JsonNode node = body.path("responseObject");
        if (node.isArray()) {
            node.forEach(out::add);
            return out;
        }
        JsonNode content = node.path("content");
        if (!content.isArray()) content = body.path("object").path("content");
        if (content.isArray()) content.forEach(out::add);
        return out;
    }

    /** Total rows the server reports, if it says. */
    public OptionalLong total() {
        if (body == null) return OptionalLong.empty();
        for (JsonNode n : List.of(body.path("totalCount"), body.path("responseObject").path("totalElements"),
                body.path("object").path("totalElements"))) {
            if (n.isNumber()) return OptionalLong.of(n.asLong());
        }
        return OptionalLong.empty();
    }

    public String describeError() {
        return "HTTP " + httpStatus + ", success=" + success() + ", statusCode=" + statusCode()
                + ", message=" + message();
    }
}
