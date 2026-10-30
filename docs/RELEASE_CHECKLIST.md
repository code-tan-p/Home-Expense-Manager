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
| Edit name, phone, job after creation | MISSING | needs `WorkerService.update` and profile page |
| Deactivate with last day, rejoin | DONE | `/staff/{id}/stop`, `/restart` |
| Six pay models | DONE | monthly, per day, per hour, per litre, per task (fixed + variable: LATER) |
| Effective-dated rate changes | DONE | `/staff/{id}/pay` |
| Weekly schedule per worker, weekly offs | DONE | `/staff/{id}/days` |
| Employment history, rate history view | BACKEND | `WorkerService.employmentHistory`, `PayService.rateHistory` |
| Worker-specific holidays | MISSING | needs migration |
| Notes, documents, emergency contact | BACKEND (notes) / LATER | `Worker.notes` has no field |

### 2. Daily attendance (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Daily dashboard, mark done / absent / leave / holiday | DONE | `HomeController`, `index.html` |
| Partial attendance, hours, litres | DONE | Today screen inputs |
| Undo | DONE | `/work/undo` |
| Extra / unscheduled work form | BACKEND | `DailyWorkService.recordUnscheduled` |
| Correction history, edit by audited correction | BACKEND | `DailyWorkService.history/correct` |
| Notes per record | MISSING | |
| Missing attendance list | PARTIAL | counts only; dedicated list MISSING |
| Calendar view per worker | DONE | `/calendar` |
| Attendance percentage | MISSING | |

### 3. Earnings engine (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Automatic monthly earnings, all models, pro-rata | DONE | `EarningsCalculator` |
| Integer paise, effective-dated rates | DONE | `Money` |
| Monthly preview, missing-attendance warnings | DONE | `/pay` |
| Finalize and lock | DONE | `/pay/close`, `FinalizedEarningsGuard` |
| Line-by-line breakdown screen | MISSING | `EarningsService.view` |
| Reopen with audit, review lines, confirm unrecorded | BACKEND | `EarningsService.reopenEarnings/reviewLine/confirmUnrecorded` |
| Bonuses, deductions, overtime, custom components | MISSING | `FinancialAdjustment` exists; UI LATER |

### 4. Payments, advances, settlements (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Partial and full payments, date, method | DONE | `/pay/payment` |
| Reference / note on payment | BACKEND | `Payment.reference/note` |
| Advance given, balance shown | DONE | `/pay/advance` |
| Advance recovery history | BACKEND | `AdvanceRecovery` |
| Payment reversal / correction | BACKEND | `PaymentService.previewReplacement/confirmReplacement` |
| Settlement history, reopen | BACKEND | `SettlementService` |
| Carry-forward | BACKEND | `SettlementService.correctCarry` |
| Printable receipt / salary slip | MISSING | |

### 5. Household expenses (P1)
| Item | Status |
| --- | --- |
| Daily expenses, categories, custom categories, method, date, notes | MISSING |
| Recurring, monthly summary, filter, audited edits | MISSING |
| Budgets, trends, split, bills, receipt images | LATER |

### 6. Dashboard (P1)
| Item | Status |
| --- | --- |
| Staff count, today's attendance, pending, month earned / paid / outstanding / advances, recent payments, alerts | MISSING |
| Charts and trends | LATER |

### 7. Backup, recovery, integrity (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Backup download, validated restore with confirmation | DONE | `BackupService` |
| Automatic daily backup, keep 14, version check | DONE | per household |
| Foreign keys, migrations, transactions, audit log, no silent delete | DONE | Flyway V1-V9 |
| Backup history list | MISSING | |
| Off-site copy, encryption, recovery drill | LATER / MISSING | drill added in P0-7 |

### 8. User experience (P0/P1)
| Item | Status |
| --- | --- |
| Clean navigation, mobile layout, confirmations, undo, empty states, validation, messages, rupee format | DONE |
| Search and filter | MISSING |
| Dark mode, shortcuts, Hindi / Punjabi | LATER |
| Phone: bottom tab bar, one-tap litre buttons, bigger Still-to-pay | MISSING (P1b) |

### 9. Security and reliability (P0)
| Item | Status | Where / note |
| --- | --- | --- |
| Accounts, one private database per household | DONE | `SignUpTest` |
| CSRF, server-side validation, SQL injection safe, restricted restore | DONE | `BreakItTest` |
| Sign-in lock-out, sign-up limits, recovery code | DONE | |
| Tests (unit, integration, edge cases), health check | DONE | 389 tests |
| Email one-time-code reset and email verification | MISSING (P1b) | needs owner's SMTP as server settings |
| Secure cookie default when public, proxy-aware client address | MISSING (P1b) | |
| Encrypted database, PIN, tamper-evident log | LATER | |

### 10. Reports and exports (P1)
| Item | Status |
| --- | --- |
| Monthly salary, worker earnings, payment history, outstanding, advances, attendance reports with date range | MISSING |
| CSV export, print-friendly pages | MISSING |
| PDF | LATER (browser "Save as PDF" from the print page) |

## B. Acceptance checklist (29 items)

Tick each with its proof (test name or manual check).

**1. Core functionality**
- [ ] Every worker has an independent payment model and schedule.
- [ ] Attendance handles present, absent, leave, holiday, partial.
- [ ] All pay models calculate correctly.
- [ ] Rate changes apply only from their effective date.
- [ ] Missing attendance cannot silently become zero earnings.
- [ ] Additional work is accounted for.

**2. Financial correctness**
- [ ] All money is exact to the paisa.
- [ ] Multiple partial payments reconcile.
- [ ] Advances are recovered by the defined rules.
- [ ] Overpayments and unpaid balances are handled.
- [ ] Every settlement has an explainable breakdown (screen).
- [ ] Corrections keep the original history (screen).

**3. Data safety**
- [ ] Backup is consistent and restorable.
- [ ] Restore validates before replacing live data.
- [ ] Interrupted transactions leave no partial records.
- [ ] Migrations work on existing installations.
- [ ] Audit log shows what changed and when.

**4. Usability**
- [ ] A new household can set up without help.
- [ ] Daily attendance is quick to enter.
- [ ] Every salary amount can be explained.
- [ ] Errors say how to fix the problem.
- [ ] History and reports are easy to find.
- [ ] Works on desktop and phone.

**5. Release readiness**
- [ ] Unit and integration tests pass.
- [ ] Business-rule edge cases are tested.
- [ ] No critical security issue remains.
- [ ] Runs without internet after installation.
- [ ] Install and upgrade steps are documented.
- [ ] Backup and recovery procedures are tested.

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
