package in.toucan.monitor.tests;

import in.toucan.monitor.collector.*;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-only suite: every catalogued dataset answers with a successful envelope (success:true,
 * not the HTTP-200 NOTUSED pattern), and the whole suite's network log contains only allowlisted reads.
 */
public class ApiReadOnlyTest {

    static final List<ApiCall> CALLS = Collections.synchronizedList(new ArrayList<>());
    ToucanClients clients;

    @BeforeClass
    public void setUp() {
        ToucanConfig env = ToucanConfig.fromEnv();
        if (!env.hasCredentials()) throw new SkipException("TOUCAN_USERNAME / TOUCAN_PASSWORD not set");
        ToucanConfig cfg = new ToucanConfig(env.uamBaseUrl(), env.candsBaseUrl(), env.accountingBaseUrl(),
                env.username(), env.password(), 5, 1, Duration.ofSeconds(60), env.relaxedHttps());
        clients = new ToucanClients(cfg, CALLS::add);
    }

    @DataProvider(name = "datasets")
    public Object[][] datasets() {
        return Datasets.all().stream()
                .filter(d -> !List.of(Datasets.CS_PAYMENT_TXNS, Datasets.ACC_LEDGER_STATEMENT, Datasets.ACC_RRN_POSTINGS)
                        .contains(d.key()))
                .map(d -> new Object[]{d.key()}).toArray(Object[][]::new);
    }

    @Test(dataProvider = "datasets", groups = "api")
    public void datasetAnswersSuccessfully(String key) {
        DatasetSpec spec = Datasets.get(key);
        Map<String, Object> extra = key.equals(Datasets.ACC_TRIAL_BALANCE) ? Map.of("category", "group") : Map.of();
        LocalDate to = LocalDate.now();
        CollectionResult r = clients.collector.collect(spec, to.minusDays(7), to, extra);
        assertThat(r.ok()).as(key + ": " + r.error()).isTrue();
    }

    @AfterClass(alwaysRun = true)
    public void networkLogHasNoWrites() {
        if (clients == null) return;
        assertThat(CALLS).noneMatch(ApiCall::blocked);
        assertThat(CALLS).allMatch(c -> ReadOnlyGuard.isAllowed(c.path()));
    }
}
