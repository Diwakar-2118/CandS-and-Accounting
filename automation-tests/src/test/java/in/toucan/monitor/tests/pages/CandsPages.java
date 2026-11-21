package in.toucan.monitor.tests.pages;

import org.openqa.selenium.WebDriver;

import java.util.Optional;

/** CandS screens are same-origin inside the host (#/cands/...). Read-only navigation only. */
public class CandsPages extends BasePage {

    private final String hostUrl;

    public CandsPages(WebDriver driver, String hostUrl) {
        super(driver);
        this.hostUrl = hostUrl;
    }

    public CandsPages open(String route) {
        driver.get(hostUrl + "/#/cands/" + route);
        return this;
    }

    public Optional<Long> globalSearchTotal() {
        return open("global-search").paginatorTotal();
    }

    public Optional<Long> paymentFilesTotal() {
        return open("payment-files").paginatorTotal();
    }
}
