# Phase 1 discovery

**Date:** 20 Nov 2026.
**Method:** I fetched the public, unauthenticated Angular bundles of the host, CandS and Accounting apps with read-only GETs and analysed them statically. I made no authenticated call and sent no POST. This step confirms endpoint paths, request-body shapes and the field names the UI binds to. It does **not** confirm response field names. Those are confirmed at runtime by `DiscoveryMain`, which writes `docs/discovery-runtime.md`.

## Authentication (corrects the original brief)

The brief assumed Keycloak directly. The host bundle shows that the UI authenticates through **Toucan UAM**:

| Step | Call | Body | Response |
|---|---|---|---|
| Login | `POST https://ops-uam.testtoucanpay.in/api/uam/auth/login` | `{"messageID":"/api/uam/auth/login","requestObject":{"identifier":…,"password":…}}` | `success, access_token, refresh_token, expires_in, refresh_expires_in, responseObject.authModeEnum, responseObject.isFirstLogin` |
| Refresh | `POST …/api/uam/auth/refreshToken` | `{"messageID":"/api/uam/auth/refreshToken","requestObject":{"token":<refresh>}}` | `object.token, object.refreshToken, object.jwtExpiryMin` |

- Possible auth modes are `PASSWORDLESS_OTP`, `PASSWORD_PLUS_OTP`, `OTP_PLUS_TOTP`, `PASSWORD_PLUS_OTP_PLUS_TOTP` and `PASSWORD_PLUS_TOTP`. The service account must use **password-only** mode, or it cannot be automated.
- Tokens are sent as `Authorization: Bearer`.
- Keycloak (realm `Toucan`, client `uam_client`) sits behind UAM. The monitor does not need its token endpoint.

## Base URLs (from the host's environment bundle)

`uamApplicationUrl=https://ops-uam.testtoucanpay.in`, `candsApplicationUrl=https://ops-cands.testtoucanpay.in`, `accountingApplicationUrl=https://ops-accounting.testtoucanpay.in`.

The **Accounting API is same-origin with its UI**: `getBaseUrl()` = `window.location` origin, so the base is `https://ops-accounting.testtoucanpay.in` + `messageID`.

Other apps declared in the same bundle (not used): ops-recon, ops-reports, ops-disputes and ops-casemgmt. **ops-recon is the likely home of the PayU recon file** (to confirm).

## Request conventions

| Helper in UI | Body |
|---|---|
| `doPostGetAll` | `{messageID, requestType:"LIST", requestObject, pageNo, pageSize}` |
| `getAll` | `{pageNo, pageSize, sortField, sortOrder, requestObject?}` |
| `doPostGetAllWithSearchObj` | `{requestObject, page, size, sortOrder, useOrOperator:false}` |
| `doPostGetAllWithUPISummarySearch` | `{messageID, requestType:"LIST", searchQuery, pageNo, pageSize}` |
| Accounting `doPostDownload` | `{messageID, downloadType, requestObject, pageNo, pageSize, sortOrder, sortField}` |

Responses come in three list shapes: `responseObject[]`, `responseObject.content[]` (Spring page) and `object.content[]`. As already observed, errors arrive as HTTP 200 with `success:false`.

## CandS list endpoints (the "To confirm" items in the brief)

| Screen | Endpoint | requestObject the screen sends |
|---|---|---|
| Global Search (load) | `POST /api/cands/transaction/getall` (getAll) | `{requestType:<chip>}`, sort `salesDateTime DESC` |
| Global Search (search) | `POST /api/cands/transaction/search` (doPostGetAll) | drawer object incl. `txnfromDate`, `txntoDate`, `tAmount` |
| Hold chip | same search | `{requestType:"ALL", transactionStatus:"HOLD"}` |
| Rolling Reserve | `POST /api/cands/transaction/search` (getAll) | `{requestType:"OTHERS", isRollingFlag:true}` |
| Move To Instant Settlement (list) | `POST /api/cands/transaction/search` (getAll) | `{requestType:"CARDS", instantFeeCalculationFailed:true}` |
| Scheme Files | `POST /api/clearing/getallfiles` | `{entryType:"FILE_RUN_INFO", file_flag:"FILES", platFormType:"ACQURING"}` |
| Payment Files | `POST /api/clearing/getallfiles` | `{…, file_flag:"PAYMENT_FILES"}` |
| Reports | `POST /api/clearing/getallfiles` | `{…, file_flag:"REPORTS"}` |
| Processed Files | `POST /api/clearing/getallprocessedfiles` | `{entryType:"PROCESSED_FILES_INCOMING"}` |
| Scheme Reject Txns | `POST /api/txn/getrejtxndetails` | `{entryType:"reject_transactions"}` |
| Payments | `POST /api/merPayReq/getAllMerPay` | `{recordIdentifier:"CREDIT"\|"DEBIT"}` |
| Payment details → Show Transactions | `POST /api/merPayReq/getTxns` | `{runNumber, merchantNumber, dbOrcrToMerchant}` |
| Journal Voucher | `POST /api/clearing/getalljv` | `{entryType:"INC_NETAMOUNT_JV"}` |
| Bulk Download | `POST /api/bulkdownload/getall` | sort `triggerDateTime DESC` |
| UPI Summary BDF | `POST /api/upi-summary-bdf/getall` | `searchQuery:{}` |

Write endpoints seen and **blocked** by `ReadOnlyGuard`:

- `instantretry` (Move to Instant Settlement)
- `rollingreserve/txnhold` and `updatetxn`
- `txn/holdrelease`
- `feescrn/trigger` and `manualoutgoingtrigger`
- `stubdata/update`
- `ird/create`
- `schemeconfig/update`
- `generalconfig/*`
- `journalvoucher/jvtrigger`
- `upi-summary-bdf/flag`
- `ftxpaymentreqres/payreq`
- `txn/rejectrepost`

The guard also blocks every other path that is not on its allowlist.

## Accounting endpoints (the brief's section 4.3)

| Screen | Constant | Endpoint | Body |
|---|---|---|---|
| Trial Balance | TRAIL_BALANACE_GETALL | `/api/accounting/trialbalance-getall` | doPostDownload `{downloadType:"PDF", requestObject:{fromDate,toDate,category: group\|subgroup\|ledger}, sortField:"accountGroup"}`, columns `accountGroup/subGroup/accountName, debit, credit` |
| Accounting-Entry statement | ACCOUNTING_ENTRY_GETALL | `/api/accounting/getDetailsEntry` | `{entryType:<ledger>, fromDate, toDate}`, columns `accountingDate, descriptionType, debitCount, debitAmount, creditCount, creditAmount, balance` |
| **Accounting-Entry RRN search** | ACCOUNTING_RRNSEARCH | `/api/accounting/searchAllEntries` | `{rrn}`, rows `subModule, ledgerName, debitAmount, creditAmount, transactionDateTime`, plus top-level `netZero` |
| Merchant-Entry | MERCHANT_ENTRY_SEARCH | `/api/merchant-ledger/getall` | `{fromDate, toDate, …}` |
| Transaction Types | TXN_TYPE_GETALL | `/api/transactiontype/getall` | getAll, fields `transactionCode, transactionName, accountDetails[{accountName, creditOrDebit}]` |
| Chart of Accounts | CHART_OF_ACCOUNTS_GETALL | `/api/ledgeraccount/getall` | getAll |
| Account Groups / Sub-Groups | | `/api/group/getall`, `/api/subgroup/getall` | getAll |
| Periods / Fiscal Year | PERIOD_GETALL / FISCAL_YEAR_GETALL | `/api/fiscalperiod/getall`, `/api/fiscalyear/getall` | fields `periodName, status, startDate, endDate`; `fiscalYearCode, …` |
| Ledger Adjustment | LEDGER_ADJUSTMENT_GETALL | `/api/ledger-adjustment/getall` | detail `txnArray[{merchantNumber, rrn, creditAmount, debitAmount, platform}]` |

**Important finding:** the RRN search gives transaction-level accounting references. C&S→Accounting validation therefore runs **per transaction** (rules R08–R11), not only at batch level.

## Still to confirm (cannot be settled without credentials or business input)

1. Response field names for the fee breakdown (MDR, CGST, SGST, fee and payment amount) and for the payment amount on `getAllMerPay`. Rules R01 and R02 stay NOT_EVALUATED until `monitor.fields.*` is set.
2. Whether `subModule` in RRN postings is the TT code. R09 stays NOT_EVALUATED until `monitor.fields.posting.ttCode` is set.
3. The value format `getDetailsEntry` expects in `entryType` (ledger name or code) → `monitor.statement-ledgers`.
4. The PayU recon file's location (ops-recon? Payment Files? SFTP?) and its columns → `monitor.payu-recon`.
5. UPI MSR/MPR: is it UPI Summary BDF plus `Merchant_Payout_Report` payment files, or does it live in ops-recon?
6. Business calendar for expected files and reports, JV window, and posting SLAs per TT code.
7. What "AP" stands for in the account-group prefixes.
8. Whether a Swagger/OpenAPI document exists. None is referenced in the bundles.
