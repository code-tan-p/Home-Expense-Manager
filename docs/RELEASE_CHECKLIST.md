# Release checklist: Home Expense Manager

This file is the single source of truth for "is the deployment successful". Every piece of work updates the
status here. Last updated: 2026-10-30 (commit 21adb96 plus this file).

Status words: **DONE** (screen and test exist), **BACKEND** (service exists, no screen yet), **MISSING**,
**LATER** (planned, not needed for v1), **WON'T** (decided against, with reason).

## Definition of "deployed successfully"

1. Every **P0** line below is DONE.
2. Every **P1** line is DONE or listed under LATER with a reason.
3. `./mvnw clean verify` is green (test count only grows).
4. `docker compose up` gives a healthy container; data survives a restart; a backup restore drill passes.
5. The README is followed literally on a clean clone and works.
6. The 29 acceptance items in section B are ticked, each with its proof.
7. Nothing is pushed or released without the owner saying so.

## A. Feature checklist (10 areas)

### 1. Worker management (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Add worker (name, job, contact, joining date) | DONE | `StaffController`, `staff.html` |
| Edit name, phone, notes after creation | DONE | `WorkerProfileController`, `worker-profile.html`, `WorkerProfileTest` (job stays fixed on purpose) |
| Deactivate with last day, rejoin | DONE | `/staff/{id}/stop`, `/restart` |
| Six pay models | DONE | monthly, per day, per hour, per litre, per task (fixed + variable: LATER) |
| Effective-dated rate changes | DONE | `/staff/{id}/pay` |
| Weekly schedule per worker, weekly offs | DONE | `/staff/{id}/days` |
| Employment history, rate history view | DONE | worker profile page |
| Worker-specific holidays | MISSING | needs migration |
| Notes | DONE | worker profile; documents and emergency contact LATER |

### 2. Daily attendance (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Daily dashboard, mark done / absent / leave / holiday | DONE | `HomeController`, `index.html` |
| Partial attendance, hours, litres | DONE | Today screen inputs |
| Undo | DONE | `/work/undo` |
| Extra / unscheduled work form | DONE | "Add extra or missed work" on `/attendance/{id}` |
| Correction history, edit by audited correction | DONE | `/attendance/{id}`, `AttendanceHistoryTest` |
| Notes per record | DONE | note box on the correction form |
| Missing attendance list | DONE | "Days not recorded yet" on `/attendance/{id}` |
| Calendar view per worker | DONE | `/calendar`, and on each worker profile (litres for milk, hours for hourly, leave and holiday otherwise) |
| Attendance percentage | DONE | calendar, attendance page, reports |

### 3. Earnings engine (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Automatic monthly earnings, all models, pro-rata | DONE | `EarningsCalculator` |
| Integer paise, effective-dated rates | DONE | `Money` |
| Monthly preview, missing-attendance warnings | DONE | `/pay` |
| Finalize and lock | DONE | `/pay/close`, `FinalizedEarningsGuard` |
| Line-by-line breakdown screen | DONE | "How this was worked out" on Monthly Pay and the salary slip |
| Reopen with audit | DONE | `/settlement/{id}` (reopens the settlement and its earnings together) |
| Review lines, confirm unrecorded | BACKEND | `EarningsService.reviewLine/confirmUnrecorded` |
| Bonuses and deductions | DONE | "Bonus, deduction or correction" on `/settlement/{id}` (`SettlementScreenTest`) |
| Overtime, custom components | LATER | |

### 4. Payments, advances, settlements (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Partial and full payments, date, method | DONE | `/pay/payment` |
| Reference on payment | DONE | Monthly Pay form and payments page |
| Advance given, balance shown | DONE | `/pay/advance` |
| Advance recovery history | DONE | advances table on `/settlement/{id}` and `/reports` |
| Payment reversal / correction | DONE | `/pay/history/{id}`, `PaymentHistoryTest` |
| Settlement history, reopen | DONE | `/settlement/{id}`, `SettlementScreenTest` |
| Carry-forward | PARTIAL | shown on the month-end screen; changing the carry target has no form (LATER) |
| Printable salary slip | DONE | `/reports/slip/{id}` (print or save as PDF); receipts LATER |

### 5. Household expenses (P1)
| Item | Status |
| --- | --- |
| Daily expenses, categories, custom categories, method, date, notes | DONE |
| Recurring, monthly summary, filter, audited edits | DONE (`ExpenseTest`, migration V10; database refuses edit or delete) |
| Budgets, trends, split, bills, receipt images | LATER |

### 6. Dashboard (P1)
| Item | Status |
| --- | --- |
| Staff count, pending, month earned / paid / outstanding / advances, spending, recent payments, alerts | DONE (`/overview`, `OverviewTest`, `BackupHistoryTest`) |
| Charts and trends | LATER |

### 7. Backup, recovery, integrity (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Backup download, validated restore with confirmation | DONE | `BackupService` |
| Automatic daily backup, keep 14, version check | DONE | per household |
| Foreign keys, migrations, transactions, audit log, no silent delete | DONE | Flyway V1-V9 |
| Backup history list | DONE | `/backup` lists the nightly copies, `BackupHistoryTest` |
| Off-site copy, encryption, recovery drill | LATER / MISSING | drill added in P0-7 |

### 8. User experience (P0/P1)
| Item | Status |
| --- | --- |
| Clean navigation, mobile layout, confirmations, undo, empty states, validation, messages, rupee format | DONE |
| Search and filter | DONE | staff search box, expenses by month and category |
| Dark mode | DONE | follows the device setting |
| Shortcuts, Hindi / Punjabi | LATER |
| Phone: one-tap litre buttons, bigger Still-to-pay, one-row scrolling menu | DONE |
| Phone: bottom tab bar | LATER |

### 9. Security and reliability (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Accounts, one private database per household | DONE | `SignUpTest` |
| CSRF, server-side validation, SQL injection safe, restricted restore | DONE | `BreakItTest` |
| Sign-in lock-out, sign-up limits, recovery code | DONE | |
| Tests (unit, integration, edge cases), health check | DONE | 389 tests |
| Email one-time-code reset | DONE | `MailService`, optional: needs `SPRING_MAIL_*` and `HOME_EXPENSE_MAIL_FROM` set by the owner (tested with a stand-in mailer) |
| Email verification at sign-up | LATER | |
| Proxy-aware client address | DONE (setting `HOME_EXPENSE_FORWARD_HEADERS=framework`) | secure cookie is a setting (`HOME_EXPENSE_SECURE_COOKIE=true`), off by default for plain http |
| Encrypted database, PIN, tamper-evident log | LATER | |

### 10. Reports and exports (P1)
| Item | Status |
| --- | --- |
| Monthly salary, payments, still-to-pay, advances and attendance reports, month and date range | DONE (`/reports`, `ReportsTest`) |
| CSV export (salary, payments, expenses), print-friendly pages | DONE |
| PDF | LATER (browser "Save as PDF" from the print page) |

## B. Acceptance checklist (29 items)

Tick each with its proof (test name or manual check).

**1. Core functionality**
- [x] Every worker has an independent payment model and schedule. (`WorkerServiceIntegrationTest`, `ScheduleServiceIntegrationTest`)
- [x] Attendance handles present, absent, leave, holiday, partial. (`DailyWorkServiceIntegrationTest`, `CalendarTest`)
- [x] All pay models calculate correctly. (`Earnings*IntegrationTest`, milk litres x price in `HomeControllerTest`)
- [x] Rate changes apply only from their effective date. (`EarningsLifecycleIntegrationTest`)
- [x] Missing attendance cannot silently become zero earnings. (blockers and warnings on Monthly Pay; `EarningsGuardIntegrationTest`)
- [x] Additional work is accounted for. (`EarningsExtraWorkIntegrationTest`; the entry form is still LATER)

**2. Financial correctness**
- [x] All money is exact to the paisa. (`MoneyTest`, `FractionTest`)
- [x] Multiple partial payments reconcile. (`PaymentReplacementIntegrationTest`, `SettlementFlowIntegrationTest`)
- [x] Advances are recovered by the defined rules. (`SettlementFlowIntegrationTest`; the recovery history screen is still LATER)
- [x] Overpayments and unpaid balances are handled. (`SettlementFlowIntegrationTest`)
- [x] Every settlement has an explainable breakdown (screen). ("How this was worked out", salary slip)
- [x] Corrections keep the original history (screen). (`PaymentHistoryTest`, `AttendanceHistoryTest`, `ExpenseTest`)

**3. Data safety**
- [x] Backup is consistent and restorable. (`SignUpTest.backupAndRestore...`)
- [x] Restore validates before replacing live data. (`BreakItTest`, `OlderBackupRestoreTest`)
- [x] Interrupted transactions leave no partial records. (`EarningsAtomicityIntegrationTest`, `FinanceAtomicityIntegrationTest`)
- [x] Migrations work on existing installations. (older backup upgraded on open; earlier one-household file adopted)
- [x] Audit log shows what changed and when. (stored for every change; a screen to read it is LATER)

**4. Usability**
- [ ] A new household can set up without help.
- [ ] Daily attendance is quick to enter.
- [ ] Every salary amount can be explained.
- [x] Errors say how to fix the problem. (`BreakItTest`: no technical words on screen)
- [ ] History and reports are easy to find.
- [ ] Works on desktop and phone.

**5. Release readiness**
- [x] Unit and integration tests pass. (397 on 2026-10-30)
- [x] Business-rule edge cases are tested. (`BreakItTest`)
- [ ] No critical security issue remains.
- [x] Runs without internet after installation. (no outside calls; email is optional)
- [x] Install and upgrade steps are documented. (README)
- [x] Backup and recovery procedures are tested. (restore drill in tests; Docker restart kept the account)

Still open in section B (honest list; the list of unbuilt features is in section E): "A new household can set up without help" and "Daily attendance is quick to enter"
need a real person trying it; "Works on desktop and phone" has the phone menu and calendar fixes but no bottom tab bar;
"No critical security issue remains" has had one committee review but no outside audit.

## C. Committee findings and what we do about them

### Investor committee (verdict: NO-FUND today, 3/10, a good portfolio project)
Ranked flaws: 1 no demand evidence; 2 no business model; 3 no edge over free tools and small competitors
(ApnaStaff, Aangan, Attendoo, PagarBook, MyGate); 4 no acquisition path (web only, English only, no WhatsApp,
the worker is not a user); 5 missing basics users want; 6 worker data privacy and compliance.

| Finding | Action | Where |
| --- | --- | --- |
| No demand evidence | Business track: talk to 20-30 homemakers or society managers; pilot with 1-2 societies | outside the code |
| Missing basics | Hindi, WhatsApp reminders, one-tap Came, worker view, UPI link | LATER (P2); one-tap and tab bar in P1b |
| Trust and privacy | Off-site backup, cookie default, email verification, DPDP notice | P1b / LATER |
| Rejected as nitpicks for now | SQLite-per-household scaling, in-memory rate limiter, admin console, market-size precision, solo founder | revisit after real users |

What would change an investor's mind, cheapest first: written pain and willingness-to-pay evidence;
Hindi plus WhatsApp reminders plus one-tap attendance; a 20-50 household pilot with weekly-use tracking;
200+ weekly-active households or one paying society.

### Earlier committee (architecture, security, UX, correctness)
Done: per-household databases, sign-up, recovery code, lock-outs, 72-byte password rule, mid-month worker on Pay,
partial day with leave, today not counted as unrecorded, per-household backup/restore.
Open: Calendar makes ~60 queries per page (P1b); proxy-aware client address and secure cookie default (P1b);
bottom tab bar, litre chips, bigger Still-to-pay (P1b); Hindi (LATER).

## D. Work log
| Date | Item | Result |
| --- | --- | --- |
| 2026-10-30 | Doc created from owner's checklist, gap check and both committees | statuses above |
| 2026-10-30 | P0: earnings breakdown, payments page with corrections, attendance corrections, worker profile, overview, older-backup restore test | 396 tests green |
| 2026-10-30 | P1: reports and CSV, salary slip, expense module (V10) | 396 tests green |
| 2026-10-30 | P1b: worker calendar on profile, litre buttons, email reset, proxy setting | 397 tests green |
| 2026-10-30 | Remaining list: month-end screen, advances, bonus/deduction form, attendance %, missing days, extra-work form, advances report, backup history, spending on overview, staff search, dark mode | 400 tests green |
| 2026-10-30 | Final check: real jar smoke test of 22 pages (all 200, no error text); Docker image built, healthy, non-root, account survived a container restart | passed |

## E. Not built yet (decided, with reason)
| Item | Why not yet |
| --- | --- |
| Fixed + variable pay together | Changes the pay engine and its tests; a bonus can be added as a correction meanwhile |
| Worker-specific holidays | Needs a new table and changes to calendar and earnings; household holidays and per-day leave work today |
| Bottom tab bar on phones | The one-row scrolling menu is in place; a tab bar is a layout rewrite |
| Hindi / Punjabi | Large mechanical job (all screens); best done once the wording is final |
| Email verification at sign-up | Needs the owner's mail settings first (reset by email is built) |
| Real PDF export | The salary slip prints to PDF from the browser; a PDF library would add a dependency |
| Payment receipts, UPI reference as its own field | The reference box on a payment covers a UPI number; separate receipt page LATER |
| Audit-log screen | Every change is stored; a screen to read it is LATER |
| Loading states | Pages are server-rendered and fast; double-click protection exists on payments |
| Budgets, trends, charts, encryption, PIN, overtime | P2 by the roadmap |
