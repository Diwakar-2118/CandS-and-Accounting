package in.toucan.monitor.backend.config;

import in.toucan.monitor.engine.EngineConfig;
import in.toucan.monitor.engine.Model;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * monitor.* settings. Business inputs (SLAs, JV window, expected-file calendar, PayU recon source,
 * unconfirmed field names) default to "not supplied" so the dependent checks report NOT_EVALUATED.
 */
@ConfigurationProperties(prefix = "monitor")
public class MonitorProperties {

    /** Default evaluation window in days, ending today (IST). */
    private int windowDays = 7;
    /** Max RRN look-ups against Accounting per run. */
    private int rrnLookupsPerRun = 500;
    /** Max payment -> transactions look-ups per run. */
    private int paymentTxnLookupsPerRun = 200;
    private BigDecimal tolerance = new BigDecimal("0.01");
    private Integer jvWindowHours;
    private Integer defaultSlaHours;
    private Map<String, Integer> slaHours = new HashMap<>();
    private List<String> fileSuccessPatterns = new ArrayList<>(List.of("(?i).*SUCCESSFULLY.*",
            "(?i)successfully processed", "(?i)^completed$"));
    /** Overrides/additions to observed field paths, e.g. fields.txn.mdrAmount=[mdrAmount]. */
    private Map<String, List<String>> fields = new HashMap<>();
    private List<CalendarEntry> calendar = new ArrayList<>();
    private List<String> payuPlatformValues = new ArrayList<>(List.of("PAYU"));
    /** Ledger values to send as entryType to the Accounting-Entry statement (confirm the value format first). */
    private List<String> statementLedgers = new ArrayList<>();
    private Schedule schedule = new Schedule();
    private PayuRecon payuRecon = new PayuRecon();
    private List<User> users = new ArrayList<>();
    /** Report types the business expects each day (for "reports generated vs expected"). Empty = not supplied. */
    private List<String> expectedDailyReports = new ArrayList<>();

    public EngineConfig toEngineConfig() {
        Map<String, List<String>> f = EngineConfig.observedFieldDefaults();
        f.putAll(fields);
        List<Model.ExpectedFile> cal = calendar.stream().map(c -> new Model.ExpectedFile(c.dataset, c.fileNameRegex,
                c.days.isEmpty() ? List.of(DayOfWeek.values()) : c.days, LocalTime.parse(c.dueBy))).toList();
        return new EngineConfig(f, tolerance, jvWindowHours, defaultSlaHours, slaHours,
                fileSuccessPatterns.stream().map(Pattern::compile).toList(), cal, payuPlatformValues);
    }

    public static class CalendarEntry {
        public String dataset;
        public String fileNameRegex;
        public List<DayOfWeek> days = new ArrayList<>();
        public String dueBy = "23:59";

        public void setDataset(String v) { dataset = v; }
        public void setFileNameRegex(String v) { fileNameRegex = v; }
        public void setDays(List<DayOfWeek> v) { days = v; }
        public void setDueBy(String v) { dueBy = v; }
    }

    public static class Schedule {
        private boolean enabled = false;
        private String transactions = "0 */15 * * * *";
        private String files = "0 5 * * * *";
        private String ledger = "0 30 6 * * *";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTransactions() { return transactions; }
        public void setTransactions(String v) { transactions = v; }
        public String getFiles() { return files; }
        public void setFiles(String v) { files = v; }
        public String getLedger() { return ledger; }
        public void setLedger(String v) { ledger = v; }
    }

    /**
     * PayU recon CSV source. Disabled until the business confirms where the file lives and its
     * column names; no column names are assumed.
     */
    public static class PayuRecon {
        private boolean enabled = false;
        private String directory;
        private String fileGlob = "*.csv";
        private Map<String, String> columns = new HashMap<>();
        private String dateFormat = "yyyy-MM-dd";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
        public String getDirectory() { return directory; }
        public void setDirectory(String v) { directory = v; }
        public String getFileGlob() { return fileGlob; }
        public void setFileGlob(String v) { fileGlob = v; }
        public Map<String, String> getColumns() { return columns; }
        public void setColumns(Map<String, String> v) { columns = v; }
        public String getDateFormat() { return dateFormat; }
        public void setDateFormat(String v) { dateFormat = v; }
    }

    public static class User {
        private String username;
        private String password;
        private List<String> roles = new ArrayList<>();

        public String getUsername() { return username; }
        public void setUsername(String v) { username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { password = v; }
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> v) { roles = v; }
    }

    public int getWindowDays() { return windowDays; }
    public void setWindowDays(int v) { windowDays = v; }
    public int getRrnLookupsPerRun() { return rrnLookupsPerRun; }
    public void setRrnLookupsPerRun(int v) { rrnLookupsPerRun = v; }
    public int getPaymentTxnLookupsPerRun() { return paymentTxnLookupsPerRun; }
    public void setPaymentTxnLookupsPerRun(int v) { paymentTxnLookupsPerRun = v; }
    public BigDecimal getTolerance() { return tolerance; }
    public void setTolerance(BigDecimal v) { tolerance = v; }
    public Integer getJvWindowHours() { return jvWindowHours; }
    public void setJvWindowHours(Integer v) { jvWindowHours = v; }
    public Integer getDefaultSlaHours() { return defaultSlaHours; }
    public void setDefaultSlaHours(Integer v) { defaultSlaHours = v; }
    public Map<String, Integer> getSlaHours() { return slaHours; }
    public void setSlaHours(Map<String, Integer> v) { slaHours = v; }
    public List<String> getFileSuccessPatterns() { return fileSuccessPatterns; }
    public void setFileSuccessPatterns(List<String> v) { fileSuccessPatterns = v; }
    public Map<String, List<String>> getFields() { return fields; }
    public void setFields(Map<String, List<String>> v) { fields = v; }
    public List<CalendarEntry> getCalendar() { return calendar; }
    public void setCalendar(List<CalendarEntry> v) { calendar = v; }
    public List<String> getPayuPlatformValues() { return payuPlatformValues; }
    public void setPayuPlatformValues(List<String> v) { payuPlatformValues = v; }
    public List<String> getStatementLedgers() { return statementLedgers; }
    public void setStatementLedgers(List<String> v) { statementLedgers = v; }
    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule v) { schedule = v; }
    public PayuRecon getPayuRecon() { return payuRecon; }
    public void setPayuRecon(PayuRecon v) { payuRecon = v; }
    public List<User> getUsers() { return users; }
    public void setUsers(List<User> v) { users = v; }
    public List<String> getExpectedDailyReports() { return expectedDailyReports; }
    public void setExpectedDailyReports(List<String> v) { expectedDailyReports = v; }
}
