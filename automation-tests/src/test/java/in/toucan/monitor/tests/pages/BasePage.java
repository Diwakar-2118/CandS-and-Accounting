package in.toucan.monitor.tests.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only page objects. They navigate and read text; they expose no method that clicks an action
 * button (Move to Instant Settlement, Hold, Trigger, Submit, Create, Update, Add New, toggles).
 *
 * <p>Locators that were not captured in Phase 1 are overridable with -Dlocator.&lt;name&gt;=css.
 */
public abstract class BasePage {

    protected final WebDriver driver;
    protected final WebDriverWait wait;

    protected BasePage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(Long.getLong("ui.waitSeconds", 40)));
    }

    protected static By css(String name, String dflt) {
        return By.cssSelector(System.getProperty("locator." + name, dflt));
    }

    protected WebElement visible(By by) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(by));
    }

    /** Angular Material paginator label, e.g. "1 – 25 of 536412". */
    public Optional<Long> paginatorTotal() {
        String label = visible(css("paginatorRange", ".mat-mdc-paginator-range-label, .mat-paginator-range-label"))
                .getText();
        Matcher m = Pattern.compile("of\\s+([\\d,]+)").matcher(label);
        return m.find() ? Optional.of(Long.parseLong(m.group(1).replace(",", ""))) : Optional.empty();
    }
}
