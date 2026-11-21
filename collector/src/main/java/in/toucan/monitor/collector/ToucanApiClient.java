package in.toucan.monitor.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Read-only API client. Every call passes {@link ReadOnlyGuard}, carries a bearer token from
 * {@link TokenProvider}, is retried once after a 401, and is reported to the call listener.
 */
public class ToucanApiClient {

    private final ToucanConfig config;
    private final HttpTransport transport;
    private final TokenProvider tokens;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Consumer<ApiCall> callListener;

    public ToucanApiClient(ToucanConfig config, HttpTransport transport, TokenProvider tokens, ObjectMapper mapper,
                           Clock clock, Consumer<ApiCall> callListener) {
        this.config = config;
        this.transport = transport;
        this.tokens = tokens;
        this.mapper = mapper;
        this.clock = clock;
        this.callListener = callListener == null ? c -> { } : callListener;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public ToucanConfig config() {
        return config;
    }

    /** POST a read request and return the parsed envelope (soft errors are NOT thrown; check isOk()). */
    public ApiEnvelope post(ToucanSystem system, String path, ObjectNode body) {
        try {
            ReadOnlyGuard.check(path);
        } catch (ReadOnlyGuard.WriteAttemptBlockedException e) {
            callListener.accept(new ApiCall(clock.instant(), system, "POST", path, 0, null, null, 0, true,
                    e.getMessage()));
            throw e;
        }
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String url = config.baseUrl(system) + path;
        HttpTransport.Response r = send(system, "POST", path, () -> transport.post(url, json, tokens.token()));
        if (r.status() == 401) {
            tokens.invalidate();
            r = send(system, "POST", path, () -> transport.post(url, json, tokens.token()));
        }
        return new ApiEnvelope(r.status(), parse(r));
    }

    /** GET a file download (e.g. a report). Returns raw bytes; caller checks status. */
    public HttpTransport.Response download(ToucanSystem system, String path) {
        ReadOnlyGuard.check(path);
        String url = config.baseUrl(system) + path;
        HttpTransport.Response r = send(system, "GET", path, () -> transport.get(url, tokens.token()));
        if (r.status() == 401) {
            tokens.invalidate();
            r = send(system, "GET", path, () -> transport.get(url, tokens.token()));
        }
        return r;
    }

    /** Builds a request body for the given style. */
    public ObjectNode body(BodyStyle style, String path, Map<String, Object> requestObject, int pageNo, int pageSize,
                           String sortField, String sortOrder) {
        ObjectNode b = mapper.createObjectNode();
        JsonNode ro = mapper.valueToTree(requestObject);
        switch (style) {
            case LIST_ENVELOPE -> {
                b.put("messageID", path);
                b.put("requestType", "LIST");
                b.set("requestObject", ro);
                b.put("pageNo", pageNo);
                b.put("pageSize", pageSize);
            }
            case PAGED_FLAT -> {
                b.put("pageNo", pageNo);
                b.put("pageSize", pageSize);
                if (sortField != null) b.put("sortField", sortField);
                if (sortOrder != null) b.put("sortOrder", sortOrder);
                if (!requestObject.isEmpty()) b.set("requestObject", ro);
            }
            case SEARCH_OBJ -> {
                b.set("requestObject", ro);
                b.put("page", pageNo);
                b.put("size", pageSize);
                b.put("sortOrder", sortOrder == null ? "ASC" : sortOrder);
                b.put("useOrOperator", false);
            }
            case UPI_SEARCH -> {
                b.put("messageID", path);
                b.put("requestType", "LIST");
                b.set("searchQuery", ro);
                b.put("pageNo", pageNo);
                b.put("pageSize", pageSize);
            }
            case DOWNLOAD_LIST -> {
                b.put("messageID", path);
                // The Trial Balance screen sends downloadType PDF yet reads JSON content back (observed).
                b.put("downloadType", "PDF");
                b.set("requestObject", ro);
                b.put("pageNo", pageNo);
                b.put("pageSize", pageSize);
                b.put("sortOrder", sortOrder == null ? "ASC" : sortOrder);
                if (sortField != null) b.put("sortField", sortField);
            }
        }
        return b;
    }

    private HttpTransport.Response send(ToucanSystem system, String method, String path,
                                        java.util.function.Supplier<HttpTransport.Response> call) {
        long t0 = System.nanoTime();
        try {
            HttpTransport.Response r = call.get();
            ApiEnvelope env = isJson(r) ? new ApiEnvelope(r.status(), parse(r)) : null;
            callListener.accept(new ApiCall(clock.instant(), system, method, path, r.status(),
                    env == null ? null : env.success(), env == null ? null : env.statusCode(),
                    (System.nanoTime() - t0) / 1_000_000, false, null));
            return r;
        } catch (TokenProvider.AuthException e) {
            throw e;
        } catch (RuntimeException e) {
            callListener.accept(new ApiCall(clock.instant(), system, method, path, -1, null, null,
                    (System.nanoTime() - t0) / 1_000_000, false, e.getClass().getSimpleName() + ": " + e.getMessage()));
            throw e;
        }
    }

    private static boolean isJson(HttpTransport.Response r) {
        String ct = r.contentType();
        return ct == null || ct.contains("json") || r.bodyAsString().stripLeading().startsWith("{");
    }

    private JsonNode parse(HttpTransport.Response r) {
        try {
            return mapper.readTree(r.body() == null ? new byte[0] : r.body());
        } catch (Exception e) {
            return mapper.createObjectNode().put("_unparseable", true);
        }
    }
}
