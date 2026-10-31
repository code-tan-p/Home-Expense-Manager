# Release checklist: Home Expense Manager

The step-by-step list to finish is in [FINISH_CHECKLIST.md](FINISH_CHECKLIST.md).

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
| Daily expenses, categories, custom categories, method, date, notes | WON'T | Removed on 31 Oct 2026: owner says the app is for worker pay only. Empty tables from V10 stay in the database |
| Recurring, monthly summary, filter, audited edits | WON'T | same decision |
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
| 2026-10-31 | Owner review round: expenses removed, strong passwords, email reset link, weekdays for monthly only, DELETE confirmation, header Sign out, buttons | 399 tests green; manual tests M1 to M8 open |
| 2026-10-31 | Browser test run by an agent (owner watching): 11 of 16 checks passed; fixes G1 to G11 listed in section G | not yet fixed |
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

## F. Owner's manual review (31 Oct 2026): decisions and to-do

Decisions the owner made after trying the app in the browser pane. Each line is ticked when built and tested.

**Decisions**
1. Password: at least 9 characters with an uppercase letter, a lowercase letter, a digit and a special character; strength bar,
   show-password eye on every password box, live "passwords match" message, and a "suggest a strong password" button.
2. Forgot password: email link only (valid 30 minutes, one use). The recovery code and the Change password form are removed.
   Until the server has mail settings, nobody can reset a password (the page says so).
3. "Works on" weekdays: only for monthly-salary workers. A milkman is every day automatically. Per-day, hourly and per-task
   workers appear on Today every day; only the days they came are marked, with no "not recorded" nagging.
4. Household Expenses module: removed completely (menu, page, overview card, CSV, tests). Its empty tables stay in the database.
5. Delete account: type the word DELETE and the password (no tick box).
6. Action links are buttons, not highlighted text links, on every page.
7. One Sign out, in the header, always visible on a phone (not hidden in the scrolling menu). No second Sign out on Account.

**Build and test**
- [x] Expenses module removed
- [x] Password rules, strength bar, eye, live match, suggest button (server rule in `AccountService`, page script `static/js/password.js`; `SignUpTest`)
- [x] Email reset link; recovery code and Change password removed (`/forgot` then `/reset`; link needs the mail settings and `HOME_EXPENSE_BASE_URL`; tested with a stand-in mailer only)
- [x] Weekdays only for monthly workers; others every day without nagging (`StaffController`, `EarningsCalculator`; `BreakItTest`, `EarningsLifecycleIntegrationTest`)
- [x] Account page: delete needs DELETE + password; single header Sign out (`AccountController`, `fragments.html`)
- [x] Action links turned into buttons (staff, reports, pay, settlement, attendance, profile, login, sign-up; owner to confirm every page by eye)
- [~] Header brand readable in dark mode (done); Reports table on a phone still scrolls sideways (owner to judge)
- [~] 399 tests green on 2026-10-31 (the three tests that expected the old weekday and "not recorded" rules were rewritten); Docker re-check and push still to do

**Needs the owner to test by hand after this build** (tick each when done)
- [ ] M1. Sign up with a weak password (refused, with the reason), then a strong one; try the eye and "Suggest a strong password"; type a different second password and watch the live message.
- [ ] M2. Forgot password with the owner's real mail settings: link arrives, opens `/reset`, sets a new password, works once, expires after 30 minutes.
- [ ] M3. Add a monthly worker (weekdays shown), a per-day worker and a milkman (no weekdays shown); open Today on a Sunday and check each is listed correctly and nobody nags about "not recorded".
- [ ] M4. Close a month for a per-day worker with some days unmarked; check the pay is only for the days marked.
- [ ] M5. Delete an account: wrong word, wrong password, then the right ones; check the records are really gone and the email can sign up again.
- [ ] M6. On a real phone, light and dark mode: every screen readable, Sign out visible in the header, every action is a button, Reports table usable.
- [ ] M7. Check no Expenses menu, page (`/expenses` gives a not-found page) or overview card is left.
- [ ] M8. Docker: `docker compose up`, sign up, restart the container, sign in again.
Original list, kept for reference:
- Sign up with a weak, then a strong password; use the eye and the suggest button.
- Forgot password with real mail settings (the owner supplies `SPRING_MAIL_*`, `HOME_EXPENSE_MAIL_FROM`, `HOME_EXPENSE_BASE_URL`).
- Add a monthly, a per-day and a milk worker; check the Today screen for each.
- Delete an account with a wrong and a right confirmation.
- Check every screen on a real phone, in light and dark mode.

## G. Browser test run of 31 Oct 2026 (agent drove the browser pane, owner watched): results and fixes to do

**Manual tests**
| Test | Result |
| --- | --- |
| M1 Sign-up passwords (strength bar, eye, suggest, live match, no recovery page, one Sign out) | PASS, with the small fixes below |
| M2 Emailed reset link | NOT RUN: needs the owner's mail settings |
| M3 Weekdays only for monthly; per-day and milk workers listed every day | PASS for weekdays; FAIL for "no nagging" (G1) |
| M4 Per-day pay (2 days x 300 = 600; 1.5 L x 72 = 108) | PASS |
| M5 Delete account (wrong password, wrong word, right both, sign up again) | PASS |
| M6 Phone width, light and dark | FAIL (G2, G3, G4) |
| M7 Expenses gone | PASS |
| M8 Docker restart keeps the account | NOT RUN: Docker Desktop was off; run again when it is on |

**Fixes to do** (tick when built and tested)
- [ ] G1. Per-day, hourly and per-task workers must not nag: no "1 expected activity is not recorded yet" and no "Absent (did not tell)" button on Today for them; they must not count in "still to record" (header, Overview "Still to record today"); no "not recorded" wording on Monthly Pay; Overview "Needs attention" must stop listing every unmarked day as one long line (show a short summary with a link instead).
- [ ] G2. Dark mode: the name and Sign out buttons in the header are almost invisible (dark text on a dark header). Make them readable.
- [ ] G3. Turn the remaining text links into buttons: Overview "Record now" and "Open Monthly Pay"; Staff "Profile and history" and "Calendar"; Pay "See calendar", "Payments and corrections", "Month-end, advances and corrections"; Reports Earlier / Later, worker names and "Salary slip" links; check every other page again.
- [ ] G4. Phone layout: the menu item "Monthly Pay" is cut to "Mo..."; the Reports salary table is cut off on the right; the placeholder "Reference (optional)" is cut off; calendar cells show "Came 3" instead of the full amount.
- [ ] G5. After the server refuses a weak password, keep the name and email the person typed (do not wipe the form).
- [ ] G6. Show our own plain message for a password that is too short, not only the browser's bubble.
- [ ] G7. After sign-up, land on Overview (today it lands on Today with a welcome banner; decide which the owner prefers).
- [ ] G8. Overview "Still to record today" counts a monthly worker on a day she is scheduled to work correctly, but also counts per-day workers; fix together with G1.
- [ ] G9. Monthly Pay wording: "Came 1 days" must read "1 day"; the "26 not recorded" and "27 scheduled activities are not recorded" lines for the same worker must agree.
- [ ] G10. "How this was worked out": remove "(standard rate #2)" and show dates as "5 Oct" instead of 2026-10-05.
- [ ] G11. Re-run this whole browser test after the fixes; then M2 (needs mail settings) and M8 (needs Docker Desktop on).

## H. Fix plan for section G (how each fix will be done)

Rule used everywhere: a "day-by-day" worker is one paid per day, per hour or per task. They are only marked on days
they came; an unmarked day simply means they did not work. One shared helper decides this, so every screen agrees.

| Fix | Cause found in the code | How it will be fixed | Proof |
| --- | --- | --- | --- |
| G1 No nagging for day-by-day workers | Today's per-worker message comes from `DailyWorkService.workerDay` and does not know the pay type; Today shows Leave / Holiday / Absent buttons to everyone; Overview, Calendar, Attendance and Monthly Pay count unmarked days for everyone | One helper `PayService.isDayByDay(workerId, date)`. Today: no "not recorded" line and no Leave / Holiday / Absent buttons for them (only Done, with hours for hourly). Calendar: unmarked days show as plain "no work", not "Not recorded". Attendance page and Monthly Pay: no missing-days list or "not recorded" count for them | MockMvc test with a per-day worker: none of those words appear; pay still correct |
| G8 Overview counts | `DashboardController` adds every worker's unmarked activity and pastes the earnings blocker text | Use the same helper; "Still to record today" counts only monthly workers and the milkman. "Needs attention" becomes one short line per worker ("Ramesh: 30 days not recorded this month") with a Calendar button | Test: Overview shows the short line, never a date list |
| G2 Dark-mode header buttons | `.topbar .btn.small-btn` uses `color: var(--background)`, which is dark in dark mode | Give the header buttons a fixed light text colour and a visible border in both modes | Browser check in dark mode |
| G3 Text links to buttons | About ten links were left as plain `<a>` | Add the button style to: Overview "Record now", "Open Monthly Pay"; Staff "Profile and history", "Calendar"; Pay "See calendar", "Payments and corrections", "Month-end..."; Reports Earlier / Later and "Salary slip" (one small button per row; worker names stay plain text); then search every template for any other plain action link | Test that each page has those as `class="btn"`; browser check |
| G4 Phone layout | Menu labels are long and the menu scrolls; the Reports table is wider than a phone; the placeholder is long; the calendar tick and cross marks are broken characters in the CSS (`"¹3"` and `"¹5"` instead of the tick and cross) | Menu: shorter labels ("Pay") and the menu wraps into two even rows on a phone instead of scrolling. Reports: on a phone each worker becomes a small card (stacked rows), on a computer it stays a table. Placeholder becomes "Reference" with the word "optional" in the label. Calendar: fix the two CSS characters | Browser check at phone width |
| G5 Form wiped after a refused password | A refusal redirects back to the page, losing what was typed | Sign-up and reset catch the refusal and show the same page again with the name and email kept (passwords cleared) and the message on top | Test: the response still holds the typed name and email |
| G6 Browser bubble for short passwords | `minlength="9"` on the boxes makes the browser stop the form first | Remove the browser limit; the live rule list already says "at least 9 characters", and the server message explains it | Test: an 8-character password gets our message |
| G7 Where sign-up lands | Sign-up redirects to Today | Go to Overview with the welcome message and a big "Add your first worker" button | Test: redirect to /overview |
| G9 Monthly Pay wording | Fixed word "days"; one count comes from the calendar (excludes today), the other from the earnings check (includes today) | "1 day" / "2 days"; show only one count (the calendar's) on Monthly Pay; the earnings check text stays only on the close-month error | Test for both lines |
| G10 "How this was worked out" | The saved explanation has "(standard rate #2)", "(one day line per date)" and dates like 2026-10-05 | Clean the text when it is shown (remove rate numbers and the technical note, write dates as "5 Oct"); the saved audit text stays complete and unchanged | Test on the line text |
| G11 Re-test | | Run all tests, rebuild, run the browser test agent again (owner watching), update this file, then M2 and M8 | Results written in section G |

**Still needs the owner**
- M2: mail settings for the server (for example a Gmail address with an app password): `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`,
  `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `HOME_EXPENSE_MAIL_FROM`, `HOME_EXPENSE_BASE_URL`, set as server settings, never typed in chat.
- M8: start Docker Desktop.
- After the re-test: say "push", then decide whether to merge the pull request into `main`.

**Order of work:** G2 and G4's CSS first (smallest, visible at once), then G1 + G8 (one helper), G9, G10, G3, G5 + G6 + G7, then G11.
One commit per group, tests green each time.
