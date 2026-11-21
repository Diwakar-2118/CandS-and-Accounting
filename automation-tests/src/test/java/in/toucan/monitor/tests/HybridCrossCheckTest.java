package in.toucan.monitor.tests;

import in.toucan.monitor.collector.ApiEnvelope;
import in.toucan.monitor.collector.Datasets;
import in.toucan.monitor.collector.DatasetSpec;
import in.toucan.monitor.collector.ToucanClients;
import in.toucan.monitor.collector.ToucanConfig;
import in.toucan.monitor.tests.pages.CandsPages;
import org.openqa.selenium.WebDriver;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hybrid suite: the total the API reports equals the total the screen shows, for the same default
 * query the screen issues on load (Global Search getall with requestType ALL, Payment Files list).
 */
public class HybridCrossCheckTest {

    WebDriver driver;
    ToucanClients clients;

    @BeforeClass
    public void start() {
        driver = UiSession.start();
        clients = new ToucanClients(ToucanConfig.fromEnv(), c -> { });
    }

    @AfterClass(alwaysRun = true)
    public void stop() {
        if (driver != null) driver.quit();
    }

    private long apiTotal(DatasetSpec spec, Map<String, Object> ro) {
        ApiEnvelope env = clients.api.post(spec.system(), spec.path(),
                clients.api.body(spec.style(), spec.path(), ro, 0, 25, spec.sortField(), spec.sortOrder()));
        assertThat(env.isOk()).as(env.describeError()).isTrue();
        return env.total().orElseThrow();
    }

    @Test(groups = "hybrid")
    public void globalSearchCountMatchesApi() {
        Optional<Long> ui = new CandsPages(driver, UiSession.HOST).globalSearchTotal();
        // Screen load (CWHGetAll): POST /api/cands/transaction/getall
        // {pageNo, pageSize, sortField:salesDateTime, sortOrder:DESC, requestObject:{requestType:ALL}}
        DatasetSpec onLoad = new DatasetSpec("cs.global_search_onload", "Global Search on load",
                in.toucan.monitor.collector.ToucanSystem.CANDS, "/api/cands/transaction/getall",
                in.toucan.monitor.collector.BodyStyle.PAGED_FLAT, Map.of("requestType", "ALL"), "salesDateTime", "DESC",
                null, null, java.util.List.of("id"), "#/cands/global-search", false);
        long api = apiTotal(onLoad, onLoad.baseRequestObject());
        assertThat(ui).contains(api);
    }

    @Test(groups = "hybrid")
    public void paymentFilesCountMatchesApi() {
        Optional<Long> ui = new CandsPages(driver, UiSession.HOST).paymentFilesTotal();
        DatasetSpec spec = Datasets.get(Datasets.CS_PAYMENT_FILES);
        assertThat(ui).contains(apiTotal(spec, spec.baseRequestObject()));
    }
}
