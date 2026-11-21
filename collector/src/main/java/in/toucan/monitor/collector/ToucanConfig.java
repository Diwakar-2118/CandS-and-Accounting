package in.toucan.monitor.collector;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Connection settings. Credentials come only from the environment (or a secrets store that
 * populates it) and are never logged: {@link #toString()} redacts them.
 *
 * <p>Base URLs default to what the UAT host's own environment bundle declares
 * ({@code uamApplicationUrl}, {@code candsApplicationUrl}, {@code accountingApplicationUrl}).
 */
public record ToucanConfig(
        String uamBaseUrl,
        String candsBaseUrl,
        String accountingBaseUrl,
        String username,
        String password,
        int pageSize,
        int maxPages,
        Duration timeout,
        boolean relaxedHttps) {

    public static final String DEFAULT_UAM = "https://ops-uam.testtoucanpay.in";
    public static final String DEFAULT_CANDS = "https://ops-cands.testtoucanpay.in";
    public static final String DEFAULT_ACCOUNTING = "https://ops-accounting.testtoucanpay.in";

    public ToucanConfig {
        Objects.requireNonNull(uamBaseUrl, "uamBaseUrl");
        Objects.requireNonNull(candsBaseUrl, "candsBaseUrl");
        Objects.requireNonNull(accountingBaseUrl, "accountingBaseUrl");
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be > 0");
        if (maxPages <= 0) throw new IllegalArgumentException("maxPages must be > 0");
    }

    /** Reads TOUCAN_* variables from the given environment map. */
    public static ToucanConfig fromEnv(Map<String, String> env) {
        return new ToucanConfig(
                env.getOrDefault("TOUCAN_UAM_URL", DEFAULT_UAM),
                env.getOrDefault("TOUCAN_CANDS_URL", DEFAULT_CANDS),
                env.getOrDefault("TOUCAN_ACCOUNTING_URL", DEFAULT_ACCOUNTING),
                env.get("TOUCAN_USERNAME"),
                env.get("TOUCAN_PASSWORD"),
                Integer.parseInt(env.getOrDefault("TOUCAN_PAGE_SIZE", "100")),
                Integer.parseInt(env.getOrDefault("TOUCAN_MAX_PAGES", "200")),
                Duration.ofSeconds(Long.parseLong(env.getOrDefault("TOUCAN_TIMEOUT_SECONDS", "60"))),
                Boolean.parseBoolean(env.getOrDefault("TOUCAN_RELAXED_HTTPS", "false")));
    }

    public static ToucanConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    public String baseUrl(ToucanSystem system) {
        return switch (system) {
            case UAM -> uamBaseUrl;
            case CANDS -> candsBaseUrl;
            case ACCOUNTING -> accountingBaseUrl;
        };
    }

    @Override
    public String toString() {
        return "ToucanConfig[uam=" + uamBaseUrl + ", cands=" + candsBaseUrl + ", accounting=" + accountingBaseUrl
                + ", username=" + (username == null ? "<unset>" : "<set>") + ", password=<redacted>"
                + ", pageSize=" + pageSize + ", maxPages=" + maxPages + ", timeout=" + timeout + "]";
    }
}
