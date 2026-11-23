package in.toucan.monitor.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses a fake transport; response shapes mirror the envelopes observed in Phase 1. */
class DatasetCollectorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private final List<String> posted = new ArrayList<>();
    private final List<ApiCall> calls = new ArrayList<>();

    private ToucanClients clients(Function<JsonNode, String> responder) {
        ToucanConfig cfg = new ToucanConfig("https://uam", "https://cands", "https://acc", "svc", "pw", 2, 10,
                Duration.ofSeconds(5), false);
        HttpTransport t = new HttpTransport() {
            @Override
            public Response post(String url, String json, String bearer) {
                try {
                    if (url.endsWith("/api/uam/auth/login")) {
                        return ok("{\"success\":true,\"access_token\":\"T\",\"refresh_token\":\"R\",\"expires_in\":3600,"
                                + "\"responseObject\":{\"isFirstLogin\":false,\"authModeEnum\":\"PASSWORD\"}}");
                    }
                    assertThat(bearer).isEqualTo("T");
                    posted.add(json);
                    return ok(responder.apply(om.readTree(json)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public Response get(String url, String bearer) {
                throw new UnsupportedOperationException();
            }
        };
        return new ToucanClients(cfg, t, clock, calls::add);
    }

    private static HttpTransport.Response ok(String body) {
        return new HttpTransport.Response(200, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    @Test
    void pagesThroughArrayResponsesAndSendsObservedBody() {
        ToucanClients c = clients(body -> {
            int page = body.path("pageNo").asInt();
            return page == 0
                    ? "{\"success\":true,\"statusCode\":200,\"totalCount\":3,\"responseObject\":[{\"id\":1,\"rrn\":\"a\"},{\"id\":2,\"rrn\":\"b\"}]}"
                    : "{\"success\":true,\"statusCode\":200,\"totalCount\":3,\"responseObject\":[{\"id\":3,\"rrn\":\"c\"}]}";
        });
        CollectionResult r = c.collector.collect(Datasets.get(Datasets.CS_TRANSACTIONS),
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20), Map.of());

        assertThat(r.ok()).isTrue();
        assertThat(r.records()).extracting(CollectedRecord::naturalKey).containsExactly("1", "2", "3");
        assertThat(r.totalReported()).isEqualTo(3L);
        assertThat(posted.get(0)).contains("\"messageID\":\"/api/cands/transaction/search\"")
                .contains("\"requestType\":\"LIST\"").contains("\"txnfromDate\":\"2026-11-01\"")
                .contains("\"requestType\":\"ALL\"");
    }

    @Test
    void notUsedSoftErrorMeansUnavailableNotEmpty() {
        ToucanClients c = clients(b -> "{\"success\":false,\"statusCode\":500,\"message\":\"NOTUSED\"}");
        CollectionResult r = c.collector.collect(Datasets.get(Datasets.CS_JV), null, null, Map.of());
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("NOTUSED");
        assertThat(calls).anyMatch(a -> Boolean.FALSE.equals(a.envelopeSuccess()) && a.envelopeStatusCode() == 500);
    }

    @Test
    void noRecordsMessageIsAValidEmptyResult() {
        ToucanClients c = clients(b -> "{\"success\":false,\"message\":\"No records found\"}");
        CollectionResult r = c.collector.collect(Datasets.get(Datasets.CS_ROLLING_RESERVE), null, null, Map.of());
        assertThat(r.ok()).isTrue();
        assertThat(r.records()).isEmpty();
    }

    @Test
    void snapshotKeepsDuplicateRowsAndNetZero() {
        ToucanClients c = clients(b -> "{\"success\":true,\"netZero\":false,\"responseObject\":{\"content\":["
                + "{\"ledgerName\":\"L1\",\"debitAmount\":10},{\"ledgerName\":\"L1\",\"debitAmount\":10}],\"totalElements\":2}}");
        CollectionResult r = c.collector.collect(Datasets.get(Datasets.ACC_RRN_POSTINGS), null, null,
                Map.of("rrn", "123"));
        assertThat(r.records()).hasSize(1);
        JsonNode p = r.records().get(0).payload();
        assertThat(p.path("rows")).hasSize(2);
        assertThat(p.path("netZero").asBoolean(true)).isFalse();
        assertThat(r.records().get(0).naturalKey()).isEqualTo("query:{\"rrn\":\"123\"}");
    }

    @Test
    void statusCodeZeroPagesAreReadUsingTotalElements() {
        // Shape observed on UAT for /api/clearing/getallfiles: success:true, statusCode:0, totalCount:0.
        ToucanClients c = clients(b -> b.path("pageNo").asInt() == 0
                ? "{\"success\":true,\"statusCode\":0,\"totalCount\":0,\"responseObject\":{\"content\":[{\"fileName\":\"a\",\"localTxnDate\":\"2026-11-20\",\"fileFlag\":\"F\"},{\"fileName\":\"b\",\"localTxnDate\":\"2026-11-20\",\"fileFlag\":\"F\"}],\"totalElements\":3}}"
                : "{\"success\":true,\"statusCode\":0,\"totalCount\":0,\"responseObject\":{\"content\":[{\"fileName\":\"c\",\"localTxnDate\":\"2026-11-20\",\"fileFlag\":\"F\"}],\"totalElements\":3}}");
        CollectionResult r = c.collector.collect(Datasets.get(Datasets.CS_SCHEME_FILES), null, null, Map.of());
        assertThat(r.ok()).isTrue();
        assertThat(r.records()).hasSize(3);
        assertThat(r.totalReported()).isEqualTo(3L);
    }

    @Test
    void emptyPaymentReplyWithoutSuccessFieldIsEmptyNotError() {
        ToucanClients c = clients(b -> "{\"statusCode\":0,\"totalCount\":0}");
        CollectionResult r = c.collector.collect(Datasets.get(Datasets.CS_PAYMENTS_CREDIT), null, null, Map.of());
        assertThat(r.ok()).isTrue();
        assertThat(r.records()).isEmpty();
    }

    @Test
    void rrnRecordNotFoundIsAnEmptySnapshotNotAnError() {
        // Accounting's reply on UAT for an RRN with no postings yet.
        ToucanClients c = clients(b -> "{\"success\":false,\"statusCode\":404,\"message\":\"Record Not Found\"}");
        CollectionResult r = c.collector.collect(Datasets.get(Datasets.ACC_RRN_POSTINGS), null, null, Map.of("rrn", "9"));
        assertThat(r.ok()).isTrue();
        assertThat(r.records()).singleElement().satisfies(x -> assertThat(x.payload().path("rows")).isEmpty());
    }

    @Test
    void hashIsIndependentOfKeyOrder() throws Exception {
        ToucanClients c = clients(b -> "{}");
        assertThat(c.collector.hash(om.readTree("{\"a\":1,\"b\":{\"x\":1,\"y\":2}}")))
                .isEqualTo(c.collector.hash(om.readTree("{\"b\":{\"y\":2,\"x\":1},\"a\":1}")));
    }
}
