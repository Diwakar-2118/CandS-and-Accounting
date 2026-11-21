package in.toucan.monitor.collector;

/** The Toucan UAT systems this monitor talks to. */
public enum ToucanSystem {
    /** User access management - login and token refresh only. */
    UAM,
    /** Clearing and Settlement. */
    CANDS,
    /** Accounting. */
    ACCOUNTING
}
