# House Expense Tracker

Native, offline Android household and personal ledger. Open this directory in Android Studio. The release starts with an empty ledger and asks for your name and household; PKR is the currency. Android 8.0 (API 26) or later is required.

## Build and install

Use JDK 17 or 21, Android SDK Platform 36 and Android SDK Build Tools 35.0.0 or the version selected by AGP. Android Studio can install these during sync. Set your SDK path in `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On macOS/Linux run `chmod +x gradlew`, then use `./gradlew`. The wrapper, version catalog and Kotlin DSL build files are included. Dependency downloads are needed for the first development build; the installed app needs no network.

The generated APK is `app/build/outputs/apk/debug/app-debug.apk`. Delivery copies and a portable source archive are under `delivery/`.

Debug signing keys differ between computers. If a local rebuild cannot update the delivered APK because its signature differs, export JSON first, uninstall the old app, install the new build, and restore the backup. Uninstalling removes the app-private ledger.

## Use

- **Home:** choose a month; inspect personal totals, household cost, recorded cash in/out, Net Cash Movement and outstanding bill shares. Cash movement is not a bank balance.
- **Personal:** search transactions, filter category or date range, and open a row to edit/delete it. With no date range, history follows the selected month.
- **Household:** switch between bills, member balances and exchanges. Open a member for outstanding items, payments and a running bill/loan history with an editable statement date. Archived members remain visible.
- **Add:** income, expense, shared bill, settlement, or other money. The money form's **Record type** selector also offers loans and repayments. Sender is the lender when recording an original loan. Repayments reverse that direction.
- **Shared bills:** enter amount and purpose; select payer independently from participants; select a split method. Exact shares must equal the total, percentages must equal 100.00%, and the saved preview is persisted.
- **Settlements:** select sender, receiver and date, then a bill or oldest eligible bills. The preview shows allocations and balances after payment. Loans require a specific original loan for repayment. All transfers involving you count in your cash movement; transfers between other members do not.
- **Reports:** monthly personal and household totals, category breakdowns, month-end outstanding items, CSV and a household-only share preview. Reports include recorded future dates when those dates fall within the selected reporting period.
- **Settings:** profile, members, categories, light/dark/system theme, manual JSON backup and restore. Archive referenced members/categories; permanently delete only unused ones. Your own member profile stays active.

Open ordinary records for details, editing and confirmed deletion. Shared expenses with payments allow title, category and note changes; remove or adjust linked payments before changing financial terms. Loans follow the same rule. Payments themselves can be edited or deleted, with balances recalculated immediately.

## Share through WhatsApp

Open a bill, member statement, monthly report or payment confirmation to preview its exact message. Notes are excluded unless explicitly reviewed and included. Member statements contain only that member's household and transfer records; personal transactions never enter them. Group summaries omit loans and personal records. Share opens the Android Sharesheet: choose WhatsApp or another app, select a recipient and send there. The app never claims delivery. **Copy Message** works without WhatsApp. Reopen an edited bill to share a fresh correction.

## Backup and restore

In **Settings**, choose **Export JSON backup** and select a location in the system file picker. The backup includes its format version, household and profile link, all eight entity collections, payment allocations and theme preference. Backups contain private financial information; store them accordingly.

Choose **Restore JSON backup**, select your file, review the household and record counts, and confirm replacing the current ledger. The app validates versions, IDs, relationships, dates, split totals, transfer directions and payment capacity before confirming or writing. Database replacement is one Room transaction. A failed database write rolls back; invalid imports leave the current ledger intact. Theme is applied through DataStore after database restoration. CSV reports cannot be imported as backups. Imports are limited to 20 MB to bound memory use.

App-private Room data and DataStore preferences are excluded from automatic cloud backup and device transfer; no Internet permission, account, backend, Firebase or analytics is included. Manual exports use the Storage Access Framework and require no broad storage permission.

## Implementation

Single app module, Kotlin, Compose/Material 3, Navigation Compose, ViewModel/StateFlow, coroutines, Room/KSP, DataStore and Kotlin serialization. `ui → ViewModel → repository → DAO` uses an application container and factory.

- `data/Models.kt`: eight explicit entities, stable UUIDs, date/relationship indexes, restricted foreign keys and audit timestamps.
- `domain/Accounting.kt`: exact decimal parsing and integer minor units, deterministic split rounding, allocation, reports, running histories and full-ledger validation. Financial dates are ISO local calendar dates; audit dates are epoch-millisecond instants.
- `data/LedgerRepository.kt`: serialized Room transactions for mutations and restores, preserving saved allocations on harmless payment edits.
- `ui/Messages.kt`: share text and CSV with escaping and spreadsheet formula protection.
- `app/schemas/`: schema versions 1 and 2. Version 1 is the migration fixture before the household update timestamp; the 1→2 migration adds the column without discarding records. Future migrations must retain these schemas and add explicit tests; destructive fallback is not enabled.

The repository validates a complete ledger snapshot before each atomic mutation and replaces its tables within that transaction. This favors straightforward integrity for a single-device household ledger. Very large ledgers may warrant incremental DAO writes and paginated history. Currently only PKR is supported; text is centralized in Android string resources for future Urdu translation.

## Verification

See [TEST_RESULTS.md](TEST_RESULTS.md) for commands, actual results and the device configuration. Tests cover money parsing, rounding, payer exclusion, invalid splits, settlements, loan repayment, cash movement, historical month-end balances, archiving, backup validation, persistence, migration, rollback, sharing privacy and critical Compose flows.
