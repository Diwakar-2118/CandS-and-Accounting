package in.toucan.monitor.tests;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Writes target/extent/<suite>.html. */
public class ExtentListener implements ITestListener, ISuiteListener {

    private static ExtentReports extent;
    private static final Map<String, ExtentTest> TESTS = new ConcurrentHashMap<>();

    @Override
    public void onStart(ISuite suite) {
        extent = new ExtentReports();
        ExtentSparkReporter spark = new ExtentSparkReporter("target/extent/" + suite.getName() + ".html");
        spark.config().setDocumentTitle("Toucan C&S / Accounting read-only checks");
        extent.attachReporter(spark);
        extent.setSystemInfo("Read-only", "true (ReadOnlyGuard allowlist)");
    }

    @Override
    public void onFinish(ISuite suite) {
        if (extent != null) extent.flush();
    }

    private ExtentTest test(ITestResult r) {
        String name = r.getMethod().getMethodName() + (r.getParameters().length > 0 ? " " + r.getParameters()[0] : "");
        return TESTS.computeIfAbsent(name + "#" + System.identityHashCode(r), n -> extent.createTest(name));
    }

    /** Lets tests attach evidence lines to the report. */
    public static void log(ITestResult r, String line) {
        if (extent != null) new ExtentListener().test(r).info(line);
    }

    @Override
    public void onTestSuccess(ITestResult r) {
        test(r).pass("passed");
    }

    @Override
    public void onTestFailure(ITestResult r) {
        test(r).fail(r.getThrowable());
    }

    @Override
    public void onTestSkipped(ITestResult r) {
        test(r).skip(r.getThrowable() == null ? "skipped" : r.getThrowable().getMessage());
    }
}
