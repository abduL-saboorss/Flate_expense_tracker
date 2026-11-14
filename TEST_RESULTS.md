# Verification results — 14 November 2026

| Check | Actual result |
|---|---|
| Debug APK build | Passed: `assembleDebug` |
| JUnit calculation and backup tests | **22 passed**, 0 failures, 0 errors |
| Instrumented Room and Compose tests | **6 passed**, 0 failures |
| Android lint | **0 errors, 19 warnings**; dependency-update notices and unused strings |
| APK signature | Verified, APK Signature Scheme v2 |
| APK compatibility metadata | minSdk 26, target/compile SDK 36; arm64-v8a, armeabi-v7a, x86 and x86_64 |
| Internet permission | Absent from the built APK |
| Process restart | Force-stop/relaunch preserved the saved Rs 51,000.00 test income |
| Compact form with keyboard | Manually verified the Save button stays visible above the decimal keyboard at 115% font scale; screenshot in `delivery/verification/keyboard.png` |

The final build used JDK 21, Gradle 8.14, AGP 8.13.1, Kotlin 2.2.20 and KSP. The successful build command was:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --offline
```

The device suite ran on a disposable Android 16 / API 36 x86_64 emulator at **720 × 1280 pixels, 320 dpi (360 × 640 dp), 115% system font scale**. Airplane mode was enabled and Wi-Fi was disabled. WhatsApp was not installed; clipboard sharing still worked.

The app APK and test APK were installed with `adb install --no-streaming -r`. Instrumentation ran directly through:

```powershell
adb -s emulator-5554 shell am instrument -w -r com.houseledger.app.test/androidx.test.runner.AndroidJUnitRunner
```

The final runner reported `OK (6 tests)`. Gradle's `connectedDebugAndroidTest --offline` could not resolve an uncached UTP host-harness dependency, so that Gradle harness was not used for the passing run. AndroidJUnitRunner executed the actual compiled Room and Compose tests on the emulator. Normal online Gradle device testing can download that harness. Run the UI suite on a fresh test emulator: its setup flow expects an empty ledger.

## What was exercised

| Requested behavior | Evidence |
|---|---|
| Exact decimal input and stable Rs 100 three-way split | JUnit and Compose: 33.34, 33.33, 33.33 |
| Payer outside the participants; excluded members owe nothing | JUnit |
| Reject incorrect exact amounts and percentages | JUnit and repository validation |
| Partial settlements, cash movement, overpayment prevention and deletion restoring debt | JUnit and instrumented repository/Room tests |
| Loan, specific repayment, reimbursements and unrelated transfers remain separate | JUnit and instrumented CRUD |
| Transfers between other members do not affect my cash | JUnit |
| Archived members retain obligations; unused members/categories can be managed | JUnit and instrumented CRUD |
| Earlier month-end balances survive later payments | JUnit: November bill outstanding in December, January payment excluded from December |
| JSON round trip and clean-database restore | JUnit and instrumented Room restore |
| Invalid versions, missing IDs, bad relationships, audit timestamps and excess allocations rejected | JUnit |
| Invalid restore and failed foreign-key insert leave existing data intact | Instrumented transaction/rollback tests |
| Database migration preserves records | Instrumented schema 1 → 2 migration test |
| Data survives reopening and activity recreation | Instrumented Room close/reopen and Compose activity recreation |
| Personal and household editing/deletion; all four tabs; settings reachable | Compose critical-flow test, plus repository CRUD tests |
| Bill preview figures, privacy of group summaries, member relevance and note exclusion | Instrumented message tests |
| Copy Message with no WhatsApp installed | Compose test verified the actual clipboard contents |
| Share corrections after editing | Compose bill edit and subsequent saved-data validation; previews derive from current saved data |

The Compose flow creates the household, records income, splits a bill, recreates the activity, previews/copies the bill, edits and deletes the bill, edits personal income, opens the monthly share preview and reaches restore settings. Instrumented repository tests additionally cover loans, repayments, other transfers, member/category management, linked-edit guards and backup restoration.

## Artifacts and scope

Raw evidence is in [delivery/verification](delivery/verification): JUnit XML, AndroidJUnitRunner output, the successful build log, text/HTML lint reports and the keyboard screenshot. The source archive includes this evidence too. The delivered `SHA256SUMS.txt` lists checksums for both APKs and the source archive.

Device execution covered API 36 on the emulator; a physical phone and API 26 device were not tested. Minimum-API compatibility was checked by lint. A full spoken TalkBack session, SAF file-picker selection and WhatsApp recipient/send actions were not automated. Member/note controls expose checkbox labels and state together, and Add has an explicit accessibility label. File serialization, validation, persistence and atomic restore were exercised independently of the system file picker. No message was sent to an external recipient.
