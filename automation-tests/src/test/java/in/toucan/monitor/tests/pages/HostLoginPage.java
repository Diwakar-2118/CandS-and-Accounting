package in.toucan.monitor.tests.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;

/** Host login (#/login): identifier + password, then #/home. */
public class HostLoginPage extends BasePage {

    public HostLoginPage(WebDriver driver) {
        super(driver);
    }

    public HostLoginPage open(String hostUrl) {
        driver.get(hostUrl + "/#/login");
        return this;
    }

    public void login(String user, String password) {
        visible(css("loginUser", "input[type='email'], input[name='id'], input[formcontrolname='id'], input[type='text']"))
                .sendKeys(user);
        visible(css("loginPassword", "input[type='password']")).sendKeys(password);
        // The login submit is authentication, not a business write.
        visible(css("loginSubmit", "button[type='submit']")).click();
        wait.until(ExpectedConditions.urlContains("#/home"));
    }

    public boolean onHome() {
        return driver.getCurrentUrl().contains("#/home") && !driver.findElements(By.tagName("body")).isEmpty();
    }
}
