# CandS-and-Accounting

A read-only monitoring platform for the Toucan UAT **Clearing & Settlement (CandS)** and **Accounting** apps. One dashboard shows:

- what is in C&S
- whether it reached Accounting
- whether the ledgers are correct

Every mismatch links to its source system, endpoint, record key and retrieved-at time.

## Modules

| Module | What it does |
|---|---|
| `collector` | Java 17 / Rest Assured clients. It logs in through UAM, refreshes tokens, pages through every dataset and records provenance. **`ReadOnlyGuard`** allowlists read endpoints and refuses everything else. |
| `engine` | Validation rules R01–R16 with evidence. Field names and business inputs are configuration, never guesses. |
| `backend` | Spring Boot 3, JPA, Flyway, PostgreSQL. Handles idempotent ingestion, history, scheduler, manual runs, REST API, CSV/XLSX export, RBAC and audit log. |
| `dashboard` | React, TypeScript, MUI and Recharts. Screens: Overview, Processors, Transactions (with lifecycle), Files & Reports, Accounting Validation, Ledger Health, Reconciliation, Runs & Alerts, Data Quality. |
| `automation-tests` | TestNG suites: `api`, `ui` (Selenium page objects, Accounting iframe) and `hybrid` (API total vs on-screen total). Results go to an Extent report. |

Start with `docs/phase1-discovery.md` (what was found) and `docs/source-to-dashboard-mapping.md` (every KPI → endpoint, field and formula).

## Quick start (laptop or VM that can reach the UAT URLs)

```bash
cp .env.example .env            # set TOUCAN_USERNAME/PASSWORD (read-only, password-only account), DB and dashboard passwords
docker compose up -d --build    # dashboard on http://localhost:8088
```

Without Docker (H2 file database):

```bash
mvn -DskipTests install
TOUCAN_USERNAME=... TOUCAN_PASSWORD=... java -jar backend/target/backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
cd dashboard && npm install && npm run dev          # http://localhost:5173 (proxies /api to :8080)
```

Then open **Runs & Alerts → Run validation**, or turn on the scheduler with `MONITOR_SCHEDULE_ENABLED=true`. The defaults are:

- transactions every 15 minutes
- files hourly
- ledger and trial balance daily

## Dashboard tips

- **Executive Summary** is the home page for management. The other screens are operations detail.
- **Light / Dark mode** is the button at top right. It is remembered per browser. Add `?theme=light` or `?theme=dark` to a link to force one.
- **Export PDF** (top right) opens the browser print dialog; choose "Save as PDF". The print layout:
  - is A3 portrait
  - hides navigation and filters
  - adds the period and export time
  - keeps the theme colours
  - never splits a card across pages

## Phase 1: runtime field discovery (do this first with credentials)

```bash
mvn -q -pl collector -am -DskipTests install
mvn -q -pl collector exec:java -Dexec.mainClass=in.toucan.monitor.collector.DiscoveryMain -Dexec.args=docs/discovery-runtime.md
```

This writes response **field names and types only** (no values) for every dataset, plus the network log. Use it to confirm these settings in `backend/src/main/resources/application.yml`:

- `monitor.fields.txn.mdrAmount … txn.paymentAmount`: fee breakdown (R01)
- `monitor.fields.payment.amount`: payment amount (R02)
- `monitor.fields.posting.ttCode`: is `subModule` the TT code? (R09)
- `monitor.statement-ledgers`: the `entryType` values for ledger statements (R15)

Business inputs stay unset until they are supplied. Until then their checks report **NOT_EVALUATED**, not pass:

- `jv-window-hours`
- `default-sla-hours` / `sla-hours`
- `calendar`
- `expected-daily-reports`
- `payu-recon`

## Tests

```bash
mvn test                                               # unit and integration tests (no network)
mvn -pl automation-tests test -Dsuite=api              # live UAT, read-only; needs TOUCAN_* env
mvn -pl automation-tests test -Dsuite=hybrid           # also needs Chrome
```

## Safety

- **Read-only.** Read endpoints are allowlisted and all other paths are refused, including:
  - instant settlement, hold and release
  - triggers, stub data, IRD
  - config updates, JV trigger
  - ledger adjustment creation, period and fiscal-year changes, active toggles

  Each run's network log (Runs → details) shows that no write call was made.
- Secrets come only from env or a vault. Tokens and bodies are never logged.
- PAN, BIN and account numbers are masked **before** storage.
- General Config endpoints are never called. They expose a GCP service-account key, which must be treated as compromised and rotated (raise it with the platform team).
