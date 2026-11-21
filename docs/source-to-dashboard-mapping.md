# Source-to-dashboard mapping

Every KPI and check, with its source endpoint, fields and formula. If a source's latest collection failed, the KPI shows **Not available** and the last successful retrieval time. It never shows 0.

Field names marked **(cfg)** are not confirmed yet. They are set in `monitor.fields.*` once `docs/discovery-runtime.md` confirms them.

## Overview KPIs

| KPI | Source (system, endpoint) | Fields | Formula |
|---|---|---|---|
| C&S transactions | CANDS `/api/cands/transaction/search` `{requestType:ALL, txnfromDate, txntoDate}` | `id`, `salesDateTime` | count of distinct `id` with salesDate in the filter window |
| Transaction amount | same | `txnAmount.value` | sum |
| Settled / not settled / rejected / disputed | same | `settlementStatus` (UI label "Scheme Status") | count where = SETTLED / NOT_SETTLED / SCHEME_REJECT / DISPUTED |
| Payments released vs pending | same | `paymentReleaseStatusEnum` | count COMPLETED / PENDING |
| Rolling-reserve held | CANDS `/api/cands/transaction/search` `{requestType:OTHERS, isRollingFlag:true}` | `id` | count |
| Instant-settlement exceptions | CANDS `/api/cands/transaction/search` `{requestType:CARDS, instantFeeCalculationFailed:true}` | `id` | count |
| Files today by category and status | CANDS `/api/clearing/getallfiles` (`file_flag` FILES / PAYMENT_FILES), `/api/clearing/getallprocessedfiles` | `localTxnDate`, `fileStatus` / `processingStatus` | count by mapped status for date = filter "to" |
| Reports generated vs expected | `/api/clearing/getallfiles` `file_flag:REPORTS` + `monitor.expected-daily-reports` | `localTxnDate` | generated = rows on date; expected = configured list (else Not available) |
| JV completed vs not | CANDS `/api/clearing/getalljv` `{entryType:INC_NETAMOUNT_JV}` | `fileStatus`, `date` | count by status |
| UPI BDF generated vs not | CANDS `/api/upi-summary-bdf/getall` | `isBdfGenerated`, `fileDate` | count by value |
| Accounting completed / pending / missing / failed | CANDS transactions + ACCOUNTING `/api/accounting/searchAllEntries {rrn}` | `rrn`; postings `debitAmount`, `creditAmount`; `netZero` | per transaction: COMPLETED = postings exist and DR = CR (R08); FAILED = unbalanced (R08); PENDING / MISSING = no postings, before / after the SLA (R10); NOT_CHECKED = not looked up yet |
| Trial-balance difference | ACCOUNTING `/api/accounting/trialbalance-getall` `{fromDate,toDate,category:group}` | `debit`, `credit` | Σdebit − Σcredit (R12) |
| Unbalanced adjustments | ACCOUNTING `/api/ledger-adjustment/getall` | `txnArray[].debitAmount/creditAmount` or entry-level | count of entries with ΣDR ≠ ΣCR (R13) |
| Period health | ACCOUNTING `/api/fiscalperiod/getall`, `/api/fiscalyear/getall` | `periodName, status, startDate, endDate` | R14 issue count |

## Rules

| Rule | Inputs | Logic | NOT_EVALUATED when |
|---|---|---|---|
| R01 fee arithmetic | transactions | payment = amount − MDR − MDR CGST − MDR SGST − fee − fee CGST − fee SGST (±0.01) | fee field names (cfg) not set |
| R02 payment = Σ net | `getAllMerPay`, `getTxns` | payment amount (cfg) vs Σ `netAmount` | payment amount field (cfg) not set |
| R03 released has payment | transactions, payments | `paymentReleaseStatusEnum=COMPLETED` ⇒ a payment with the same `runNumber`+`merchantNumber`(+`dbOrcrToMerchant`) exists; WARN if the payment list was truncated | source down |
| R04 JV window | JV | status ≠ Completed and older than `monitor.jv-window-hours` | (WARN when the window is not supplied) |
| R05 files | scheme/payment/report/processed files | status ∉ success patterns; Missing per `monitor.calendar` | calendar part skipped if not supplied |
| R06 UPI BDF | UPI summary BDF | `isBdfGenerated` ≠ true | source down |
| R07 decryption | payments, JV, payment files, transactions | any value containing "Decryption failed" | — |
| R08 posted & balanced | transactions + RRN postings | ΣDR = ΣCR and `netZero` ≠ false | source down |
| R09 TT mapping | RRN postings + Transaction Types | (ledger, DR/CR) ∈ the TT's configured G/L lines; per-row "Mapping not confirmed" for UNCONFIRMED codes | `posting.ttCode` (cfg) not set |
| R10 pending vs missing | transactions + RRN postings | no postings: PENDING within the SLA, MISSING after; never MISSING immediately; PENDING with a note if no SLA is configured | source down |
| R11 duplicates | RRN postings | the same (subModule, ledger, DR, CR) more than once per RRN | source down |
| R12 trial balance | trial balance | \|ΣDR − ΣCR\| > 0.01 | source down |
| R13 adjustments | ledger adjustments | ΣDR ≠ ΣCR per entry | source down |
| R14 periods | periods, fiscal years | exactly one OPEN period covers today; no gaps or overlaps; each period inside a fiscal year; a period that starts in the FY's first month starts on the FY start date | source down |
| R15 running balance | `getDetailsEntry` per `monitor.statement-ledgers` | bal(i) = bal(i−1) + DR − CR (DR positive) | no ledgers configured |
| R16 PayU recon | recon CSV (cfg) + transactions with `platformType` PAYU | per date: count, Σ amount, Σ net | recon source not configured |

## Correlation keys

| From → To | Keys |
|---|---|
| Transaction → Payment | `runNumber` + `merchantNumber` + `dbOrcrToMerchant` (`getTxns` request object) |
| Transaction → Scheme reject | `arn`, else `rrn` |
| Transaction → Accounting | `rrn` → `/api/accounting/searchAllEntries` |
| File → Report | `fileName` + `localTxnDate` + `fileFlag` (natural key) |

## Provenance

Every stored record keeps:

- system and endpoint
- request parameters (canonical JSON)
- natural key and SHA-256 content hash
- first-seen, last-seen and retrieved-at times
- the run id

Changed content goes to `source_record_history`. Each run stores one `dataset_snapshot` per query (ok / error / count / server total / truncated) and its full outbound network log in `api_call`.

## The "14 reports" in the original brief

The original brief's list of 14 reports was not provided with this prompt. Every list (transactions, files by category, validation results, matrix, mappings, trial balance, adjustments, periods, runs, alerts and PayU recon) exports to CSV and XLSX at `/api/export/{list}`, with source columns and rule outcomes. Once the business lists the 14 reports, each can be mapped to one of these exports or added as a new one.
