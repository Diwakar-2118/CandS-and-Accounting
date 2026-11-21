package in.toucan.monitor.tests;

import in.toucan.monitor.tests.pages.AccountingPages;
import in.toucan.monitor.tests.pages.CandsPages;
import org.openqa.selenium.WebDriver;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** UI-only suite: the screens the dashboard reconciles against load and show data. */
public class UiReadTest {

    WebDriver driver;

    @BeforeClass
    public void start() {
        driver = UiSession.start();
    }

    @AfterClass(alwaysRun = true)
    public void stop() {
        if (driver != null) driver.quit();
    }

    @Test(groups = "ui")
    public void globalSearchShowsATotal() {
        assertThat(new CandsPages(driver, UiSession.HOST).globalSearchTotal()).isPresent();
    }

    @Test(groups = "ui")
    public void accountingTransactionTypesListLoadsInIframe() {
        AccountingPages a = new AccountingPages(driver, UiSession.HOST).open("transactiontype-getall");
        assertThat(a.tableRowsText()).isNotEmpty();
        a.leave();
    }
}
