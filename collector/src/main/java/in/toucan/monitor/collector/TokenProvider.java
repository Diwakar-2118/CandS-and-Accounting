package in.toucan.monitor.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Obtains and refreshes bearer tokens through Toucan UAM, the same flow the host UI uses
 * (observed in the host bundle, Phase 1):
 * <pre>
 * POST {uam}/api/uam/auth/login        {"messageID":"/api/uam/auth/login","requestObject":{"identifier":u,"password":p}}
 *   -> {success, access_token, refresh_token, expires_in, refresh_expires_in, responseObject:{authModeEnum, isFirstLogin}}
 * POST {uam}/api/uam/auth/refreshToken {"messageID":"/api/uam/auth/refreshToken","requestObject":{"token":refresh}}
 *   -> {success, object:{token, refreshToken, jwtExpiryMin}}
 * </pre>
 * Tokens are never passed in URLs (unlike the host's iframe localObj/sessionObj pattern) and never logged.
 */
public class TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenProvider.class);
    private static final Duration SKEW = Duration.ofSeconds(90);
    private static final Set<String> OTP_MODES = Set.of("PASSWORDLESS_OTP", "PASSWORD_PLUS_OTP", "OTP_PLUS_TOTP",
            "PASSWORD_PLUS_OTP_PLUS_TOTP", "PASSWORD_PLUS_TOTP");

    private final ToucanConfig config;
    private final HttpTransport transport;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Consumer<ApiCall> callListener;

    private String accessToken;
    private String refreshToken;
    private Instant accessExpiry = Instant.EPOCH;
    private Instant refreshExpiry = Instant.EPOCH;

    public TokenProvider(ToucanConfig config, HttpTransport transport, ObjectMapper mapper, Clock clock,
                         Consumer<ApiCall> callListener) {
        this.config = config;
        this.transport = transport;
        this.mapper = mapper;
        this.clock = clock;
        this.callListener = callListener == null ? c -> { } : callListener;
    }

    /** Returns a valid access token, refreshing or logging in as needed. */
    public synchronized String token() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessExpiry.minus(SKEW))) return accessToken;
        if (refreshToken != null && now.isBefore(refreshExpiry.minus(SKEW))) {
            try {
                refresh();
                return accessToken;
            } catch (RuntimeException e) {
                log.warn("Token refresh failed ({}); logging in again", e.getMessage());
            }
        }
        login();
        return accessToken;
    }

    /** Forces the next {@link #token()} call to refresh (used after a 401). */
    public synchronized void invalidate() {
        accessExpiry = Instant.EPOCH;
    }

    private void login() {
        if (!config.hasCredentials()) {
            throw new AuthException("TOUCAN_USERNAME / TOUCAN_PASSWORD are not set");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("messageID", "/api/uam/auth/login");
        ObjectNode ro = body.putObject("requestObject");
        ro.put("identifier", config.username());
        ro.put("password", config.password());
        JsonNode resp = call("/api/uam/auth/login", body);

        JsonNode obj = resp.path("responseObject");
        if (obj.path("isFirstLogin").asBoolean(false)) {
            throw new AuthException("Service account must change its password first (isFirstLogin=true)");
        }
        String mode = obj.path("authModeEnum").asText("");
        String token = firstText(resp, "access_token", "/responseObject/access_token", "/object/token");
        if (token == null && OTP_MODES.contains(mode)) {
            throw new AuthException("Service account uses " + mode + "; a password-only read-only account is required");
        }
        if (token == null) throw new AuthException("Login response had no access token");
        accessToken = token;
        refreshToken = firstText(resp, "refresh_token", "/responseObject/refresh_token", "/object/refreshToken");
        long expiresIn = firstLong(resp, 3600, "expires_in", "/responseObject/expires_in");
        long refreshIn = firstLong(resp, 7 * 24 * 3600, "refresh_expires_in", "/responseObject/refresh_expires_in");
        Instant now = clock.instant();
        accessExpiry = now.plusSeconds(expiresIn);
        refreshExpiry = now.plusSeconds(refreshIn);
        log.info("Logged in to UAM; access token valid {}s", expiresIn);
    }

    private void refresh() {
        ObjectNode body = mapper.createObjectNode();
        body.put("messageID", "/api/uam/auth/refreshToken");
        body.putObject("requestObject").put("token", refreshToken);
        JsonNode resp = call("/api/uam/auth/refreshToken", body);
        String token = firstText(resp, "/object/token", "/responseObject/token", "access_token");
        if (token == null) throw new AuthException("Token missing in refresh response");
        accessToken = token;
        String rt = firstText(resp, "/object/refreshToken", "/responseObject/refreshToken", "refresh_token");
        if (rt != null) refreshToken = rt;
        JsonNode mins = resp.at("/object/jwtExpiryMin");
        accessExpiry = clock.instant().plusSeconds(mins.isNumber() ? mins.asLong() * 60 : 3600);
        long refreshIn = firstLong(resp, -1, "/object/refreshExpiresIn", "/object/refresh_expires_in", "refresh_expires_in");
        if (refreshIn > 0) refreshExpiry = clock.instant().plusSeconds(refreshIn);
    }

    private JsonNode call(String path, ObjectNode body) {
        ReadOnlyGuard.check(path);
        long t0 = System.nanoTime();
        HttpTransport.Response r;
        try {
            r = transport.post(config.uamBaseUrl() + path, mapper.writeValueAsString(body), null);
        } catch (Exception e) {
            callListener.accept(new ApiCall(clock.instant(), ToucanSystem.UAM, "POST", path, -1, null, null,
                    elapsed(t0), false, e.getClass().getSimpleName()));
            throw new AuthException("UAM call " + path + " failed: " + e.getMessage());
        }
        JsonNode json;
        try {
            json = mapper.readTree(r.body());
        } catch (Exception e) {
            json = mapper.createObjectNode();
        }
        ApiEnvelope env = new ApiEnvelope(r.status(), json);
        callListener.accept(new ApiCall(clock.instant(), ToucanSystem.UAM, "POST", path, r.status(), env.success(),
                env.statusCode(), elapsed(t0), false, null));
        if (!env.isOk()) throw new AuthException("UAM " + path + " rejected: " + env.describeError());
        return json;
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String firstText(JsonNode n, String... paths) {
        for (String p : paths) {
            JsonNode v = p.startsWith("/") ? n.at(p) : n.path(p);
            if (v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static long firstLong(JsonNode n, long dflt, String... paths) {
        for (String p : paths) {
            JsonNode v = p.startsWith("/") ? n.at(p) : n.path(p);
            if (v.isNumber()) return v.asLong();
            if (v.isTextual() && v.asText().matches("\\d+")) return Long.parseLong(v.asText());
        }
        return dflt;
    }

    public static final class AuthException extends RuntimeException {
        public AuthException(String m) {
            super(m);
        }
    }
}
