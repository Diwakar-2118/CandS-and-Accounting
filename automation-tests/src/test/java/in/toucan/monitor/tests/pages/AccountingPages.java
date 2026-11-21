package in.toucan.monitor.tests.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;

/**
 * Accounting runs in a cross-origin iframe at #/accountingnew/configurations/...; we switch into the
 * frame (the host hands the session to it) rather than copying tokens into URLs.
 */
public class AccountingPages extends BasePage {

    private final String hostUrl;

    public AccountingPages(WebDriver driver, String hostUrl) {
        super(driver);
        this.hostUrl = hostUrl;
    }

    public AccountingPages open(String route) {
        driver.switchTo().defaultContent();
        driver.get(hostUrl + "/#/accountingnew/configurations/" + route);
        By frame = css("accountingFrame", "iframe[src*='ops-accounting']");
        wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(frame));
        return this;
    }

    /** Reads the visible table rows' text of the current screen (no clicks on actions). */
    public List<String> tableRowsText() {
        visible(css("tableRow", "table tbody tr, mat-row, tr.mat-mdc-row"));
        List<WebElement> rows = driver.findElements(By.cssSelector(
                System.getProperty("locator.tableRow", "table tbody tr, mat-row, tr.mat-mdc-row")));
        return rows.stream().map(WebElement::getText).toList();
    }

    public void leave() {
        driver.switchTo().defaultContent();
    }
}
