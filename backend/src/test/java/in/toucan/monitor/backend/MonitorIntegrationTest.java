package in.toucan.monitor.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.toucan.monitor.backend.domain.RunEntity;
import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.backend.service.IngestionService;
import in.toucan.monitor.backend.service.ValidationService;
import in.toucan.monitor.collector.CollectedRecord;
import in.toucan.monitor.collector.CollectionResult;
import in.toucan.monitor.collector.Datasets;
import in.toucan.monitor.collector.ToucanSystem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitorIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired IngestionService ingestion;
    @Autowired ValidationService validation;
    @Autowired Repos.RunRepo runs;
    @Autowired Repos.SourceRecordRepo records;
    @Autowired ObjectMapper om;

    CollectedRecord rec(String ds, String key, String json) throws Exception {
        return new CollectedRecord(ds, ToucanSystem.ACCOUNTING, Datasets.get(ds).path(), "{}", key,
                Integer.toHexString(json.hashCode()), om.readTree(json), Instant.now());
    }

    @Test
    void endToEndFromStoredSourceDataToKpis() throws Exception {
        RunEntity run = new RunEntity();
        run.triggerType = "TEST";
        run.groupsCsv = "LEDGER";
        run.windowFrom = LocalDate.of(2026, 11, 1);
        run.windowTo = LocalDate.of(2026, 11, 20);
        run.startedAt = Instant.now();
        run.status = "RUNNING";
        run = runs.save(run);

        String tb = "{\"query\":{\"category\":\"group\",\"fromDate\":\"2026-11-01\",\"toDate\":\"2026-11-20\"},"
                + "\"rows\":[{\"accountGroup\":\"ASSETS\",\"debit\":993023886.31,\"credit\":0},"
                + "{\"accountGroup\":\"LIABILITIES\",\"debit\":0,\"credit\":992839306.82}]}";
        ingestion.store(new CollectionResult(Datasets.ACC_TRIAL_BALANCE, "/api/accounting/trialbalance-getall", "{}",
                true, null, List.of(rec(Datasets.ACC_TRIAL_BALANCE, "query:tb", tb)), 2L, false, Instant.now()), run.id);
        ingestion.store(new CollectionResult(Datasets.CS_TRANSACTIONS, "/api/cands/transaction/search", "{}",
                true, null, List.of(new CollectedRecord(Datasets.CS_TRANSACTIONS, ToucanSystem.CANDS,
                "/api/cands/transaction/search", "{}", "1", "h1", om.readTree(
                "{\"id\":1,\"rrn\":\"R1\",\"cardNumber\":\"4111111111111111\",\"salesDateTime\":\"2026-11-19T10:00:00\","
                        + "\"platformType\":\"PAYU\",\"settlementStatus\":\"SETTLED\",\"txnAmount\":{\"value\":500,\"code\":\"INR\"}}"),
                Instant.now())), 1L, false, Instant.now()), run.id);
        // A source that failed with the HTTP-200 NOTUSED pattern.
        ingestion.store(new CollectionResult(Datasets.CS_JV, "/api/clearing/getalljv", "{}", false,
                "Source error: HTTP 200, success=false, statusCode=500, message=NOTUSED", List.of(), null, false,
                Instant.now()), run.id);

        validation.validate(run.id, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20));

        // PAN masked before storage
        assertThat(records.findByDatasetAndNaturalKey(Datasets.CS_TRANSACTIONS, "1").orElseThrow().payload)
                .doesNotContain("4111111111111111").contains("1111");

        String body = mvc.perform(get("/api/kpis?from=2026-11-01&to=2026-11-20").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode kpis = om.readTree(body);
        JsonNode count = find(kpis, "txn.count");
        assertThat(count.path("value").asInt()).isEqualTo(1);
        JsonNode tbKpi = find(kpis, "tb.difference");
        assertThat(tbKpi.path("value").get(0).path("difference").asText()).isEqualTo("184579.49");
        JsonNode jv = find(kpis, "jv.status");
        assertThat(jv.path("available").asBoolean()).isFalse();
        assertThat(jv.path("value").isNull()).isTrue();
        assertThat(jv.path("reason").asText()).contains("NOTUSED");

        // Volume dashboard: the stored txn has no schemeCode, so it counts in Total but in no channel.
        JsonNode vol = om.readTree(mvc.perform(get("/api/volume?from=2026-11-19&to=2026-11-19")
                .with(httpBasic("viewer", "viewer-pw"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(vol.path("available").asBoolean()).isTrue();
        assertThat(vol.path("total").path("total").asLong()).isEqualTo(1);
        assertThat(vol.path("granularity").asText()).isEqualTo("hour");
        assertThat(vol.path("series")).hasSize(24);
        assertThat(vol.path("channels").get(0).path("total").asLong()).isZero();

        // Viewer cannot trigger runs; anonymous cannot read.
        mvc.perform(post("/api/runs").with(httpBasic("viewer", "viewer-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/kpis")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/export/validation?format=xlsx&from=2026-11-01&to=2026-11-20")
                .with(httpBasic("viewer", "viewer-pw"))).andExpect(status().isOk());
    }

    @Autowired in.toucan.monitor.backend.service.RunService runService;

    @Test
    void runWithoutCredentialsCompletesWithErrorsAndReleasesTheLock() throws Exception {
        String body = mvc.perform(post("/api/runs").with(httpBasic("operator", "operator-pw"))
                        .contentType("application/json").content("{\"groups\":[\"LEDGER\"]}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        long id = om.readTree(body).path("runId").asLong();
        for (int i = 0; i < 100 && runService.isRunning(); i++) Thread.sleep(200);
        assertThat(runService.isRunning()).isFalse();
        RunEntity r = runs.findById(id).orElseThrow();
        assertThat(r.status).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(r.error).contains("TOUCAN_USERNAME");
        assertThat(r.finishedAt).isNotNull();
    }

    static JsonNode find(JsonNode arr, String key) {
        for (JsonNode n : arr) if (key.equals(n.path("key").asText())) return n;
        throw new AssertionError("KPI " + key + " missing");
    }
}
