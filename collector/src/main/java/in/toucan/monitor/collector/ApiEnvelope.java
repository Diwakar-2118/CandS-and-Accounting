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

    /**
     * True when HTTP is 2xx and the body does not signal an error. Observed on UAT (Phase 1 runtime):
     * <ul>
     *   <li>success:true with statusCode 200 (transaction search, Accounting) or statusCode 0 (files, JV, BDF): OK</li>
     *   <li>success:false, statusCode:500, message:NOTUSED: error</li>
     *   <li>no success field, statusCode 0, no message, no content (merPayReq/getAllMerPay when empty): OK, empty</li>
     * </ul>
     */
    public boolean isOk() {
        if (httpStatus < 200 || httpStatus >= 300) return false;
        Integer sc = statusCode();
        boolean scOk = sc == null || sc == 0 || (sc >= 200 && sc < 300);
        if (!scOk) return false;
        if (success()) return true;
        boolean successAbsent = body != null && !body.has("success");
        return successAbsent && (message() == null || message().isBlank());
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

    /**
     * Total rows the server reports. Paged endpoints return totalCount:0 next to the real
     * responseObject.totalElements, so the largest reported figure wins.
     */
    public OptionalLong total() {
        if (body == null) return OptionalLong.empty();
        long best = -1;
        for (JsonNode n : List.of(body.path("totalCount"), body.path("responseObject").path("totalElements"),
                body.path("object").path("totalElements"))) {
            if (n.isNumber()) best = Math.max(best, n.asLong());
        }
        return best < 0 ? OptionalLong.empty() : OptionalLong.of(best);
    }

    public String describeError() {
        return "HTTP " + httpStatus + ", success=" + success() + ", statusCode=" + statusCode()
                + ", message=" + message();
    }
}
