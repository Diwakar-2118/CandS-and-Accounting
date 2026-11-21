package in.toucan.monitor.tests;

import in.toucan.monitor.collector.ToucanConfig;
import in.toucan.monitor.tests.pages.HostLoginPage;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.testng.SkipException;

/** Shared browser session for UI and hybrid suites. Selenium Manager resolves the driver. */
final class UiSession {

    static final String HOST = System.getProperty("toucan.hostUrl",
            System.getenv().getOrDefault("TOUCAN_HOST_URL", "https://ops-host.testtoucanpay.in"));

    private UiSession() {
    }

    static WebDriver start() {
        ToucanConfig cfg = ToucanConfig.fromEnv();
        if (!cfg.hasCredentials()) throw new SkipException("TOUCAN_USERNAME / TOUCAN_PASSWORD not set");
        ChromeOptions o = new ChromeOptions();
        if (!Boolean.getBoolean("ui.headed")) o.addArguments("--headless=new");
        o.addArguments("--window-size=1600,1000");
        WebDriver d = new ChromeDriver(o);
        new HostLoginPage(d).open(HOST).login(cfg.username(), cfg.password());
        return d;
    }
}
