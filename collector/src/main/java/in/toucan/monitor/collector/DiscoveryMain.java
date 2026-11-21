package in.toucan.monitor.collector;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 1 runtime discovery. Logs in with the read-only service account, requests a small first
 * page from every catalogued read endpoint and writes the response FIELD NAMES and JSON types
 * (never values) to a Markdown file. Use its output to confirm field mappings before trusting KPIs.
 *
 * <pre>
 * TOUCAN_USERNAME=... TOUCAN_PASSWORD=... java -cp collector.jar in.toucan.monitor.collector.DiscoveryMain docs/discovery-runtime.md
 * </pre>
 */
public final class DiscoveryMain {

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/discovery-runtime.md");
        ToucanConfig base = ToucanConfig.fromEnv();
        ToucanConfig cfg = new ToucanConfig(base.uamBaseUrl(), base.candsBaseUrl(), base.accountingBaseUrl(),
                base.username(), base.password(), 5, 1, base.timeout(), base.relaxedHttps());
        List<ApiCall> calls = new ArrayList<>();
        ToucanClients c = new ToucanClients(cfg, calls::add);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(7);

        StringBuilder md = new StringBuilder("# Runtime discovery\n\nGenerated ").append(Instant.now())
                .append(" against ").append(cfg.candsBaseUrl()).append(" and ").append(cfg.accountingBaseUrl())
                .append(".\nOnly field names and JSON types are recorded. Window: ").append(from).append("..").append(to)
                .append("\n\n");
        for (DatasetSpec spec : Datasets.all()) {
            Map<String, Object> extra = switch (spec.key()) {
                case Datasets.ACC_TRIAL_BALANCE -> Map.of("category", "group");
                case Datasets.CS_PAYMENT_TXNS, Datasets.ACC_LEDGER_STATEMENT, Datasets.ACC_RRN_POSTINGS,
                        Datasets.ACC_MERCHANT_LEDGER -> null; // need a parent key; sampled below when possible
                default -> Map.of();
            };
            md.append("## ").append(spec.key()).append(" — ").append(spec.label()).append("\n\n")
                    .append("`").append(spec.system()).append(" POST ").append(spec.path()).append("` style ")
                    .append(spec.style()).append(", screen `").append(spec.screen()).append("`\n\n");
            if (extra == null) {
                md.append("_Needs a parent key (payment, ledger or RRN); sampled by the backend run._\n\n");
                continue;
            }
            CollectionResult r = c.collector.collect(spec, from, to, extra);
            if (!r.ok()) {
                md.append("**Unavailable:** ").append(r.error()).append("\n\n");
                continue;
            }
            md.append("Rows on first page: ").append(r.records().size()).append(", server total: ")
                    .append(r.totalReported()).append("\n\n");
            Map<String, String> fields = new TreeMap<>();
            for (CollectedRecord rec : r.records()) {
                JsonNode p = rec.payload();
                if (spec.snapshot() && p.has("rows")) p.path("rows").forEach(row -> inventory(row, "", fields));
                else inventory(p, "", fields);
            }
            if (fields.isEmpty()) md.append("_No rows to inspect._\n\n");
            else {
                md.append("| field | type |\n|---|---|\n");
                fields.forEach((k, v) -> md.append("| `").append(k).append("` | ").append(v).append(" |\n"));
                md.append('\n');
            }
        }
        md.append("## Network log\n\n| method | system | path | http | success | statusCode | ms | blocked |\n|---|---|---|---|---|---|---|---|\n");
        for (ApiCall a : calls) {
            md.append("| ").append(a.method()).append(" | ").append(a.system()).append(" | ").append(a.path())
                    .append(" | ").append(a.httpStatus()).append(" | ").append(a.envelopeSuccess()).append(" | ")
                    .append(a.envelopeStatusCode()).append(" | ").append(a.durationMs()).append(" | ")
                    .append(a.blocked()).append(" |\n");
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, md.toString());
        System.out.println("Wrote " + out.toAbsolutePath() + " (" + calls.size() + " calls)");
    }

    static void inventory(JsonNode node, String prefix, Map<String, String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String k = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                JsonNode v = e.getValue();
                if (v.isObject()) inventory(v, k, out);
                else if (v.isArray()) {
                    out.merge(k, "array", (a, b) -> a);
                    if (v.size() > 0 && v.get(0).isObject()) inventory(v.get(0), k + "[]", out);
                } else out.merge(k, v.getNodeType().name().toLowerCase(), (a, b) -> a.equals("null") ? b : a);
            });
        }
    }

    private DiscoveryMain() {
    }
}
