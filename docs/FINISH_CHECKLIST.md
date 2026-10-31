# Finish checklist: Home Expense Manager

The last list. When every box is ticked, the app is finished and deployed.
Each item says **what** to do, **how** it will be done, and **how to see it work in the browser**.
Details and history stay in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) (sections G and H).

Browser checks use the app at http://127.0.0.1:8080/ on a fresh test account.
"Phone width" means the browser pane set to mobile size. "Dark mode" means the pane set to dark.
A **day-by-day worker** is anyone paid per day, per hour or per task.

Legend (31 Oct 2026 browser run by the agent: Part 4 passed except where marked): `[ ]` to do · `[~]` built and covered by automated tests, still to be seen working in the browser (Part 4) · `[x]` done and seen working in the browser · `[owner]` only the owner can do it.

---

## Part 1. Fixes from the browser test

### 1.1 Day-by-day workers are never nagged (G1, G8)
- [x] **What:** no "not recorded" message, no Leave / Holiday / Absent buttons, not counted in "still to record", no missing-day lists.
- **How:** one helper `PayService.isDayByDay(workerId, date)`, used by Today (`HomeController`, `index.html`), Overview
  (`DashboardController`), Calendar (`CalendarService`: unmarked day = "no work"), Attendance page and Monthly Pay.
  Overview "Needs attention" becomes one short line per worker with a Calendar button.
- **See it work:** add a per-day worker "Dayo". Open Today on a past weekday without marking him: only a **Done** button,
  no warning line, and the header count does not include him. Open Overview: "Still to record today" leaves him out and
  "Needs attention" has short lines only. Open his calendar: unmarked days say "no work".

### 1.2 Header buttons readable in dark mode (G2)
- [x] **What:** your name and **Sign out** clearly visible in dark mode.
- **How:** fixed light text colour and a visible border for `.topbar .btn.small-btn` in `app.css`.
- **See it work:** dark mode, any page: name and Sign out are easy to read.

### 1.3 Every action is a button (G3)
- [x] **What:** no plain underlined action links left.
- **How:** add the button style in `overview.html`, `staff.html`, `pay.html`, `reports.html` (Earlier / Later, one
  "Slip" button per row), then search all templates for any other plain `<a>` used as an action.
- **See it work:** visit Overview, Staff, Monthly Pay, Reports, Calendar, Attendance, Month-end, Payments, Profile:
  every action is a rounded button.

### 1.4 Phone layout (G4)
- [x] **What:** menu not cut off; Reports readable; placeholder not cut; calendar marks correct.
- **How:** shorter menu labels ("Pay") and a menu that wraps into two rows on a phone; Reports shows one card per
  worker on a phone; placeholder "Reference"; fix the broken calendar characters (`"¹3"`, `"¹5"`) to a tick and a cross.
- **See it work:** phone width: every menu item is fully readable without sideways scrolling; Reports shows cards
  with all numbers visible; calendar shows ✓ on days they came and ✕ on days they did not come.

### 1.5 Sign-up form keeps what was typed (G5, G6)
- [x] **What:** a refused password keeps the name and email; short passwords get our own message.
- **How:** `LoginController` shows the sign-up / reset page again with name and email filled instead of reloading;
  remove `minlength` from the password boxes in `signup.html` and `reset.html`.
- **See it work:** sign up with `Weak#1` (too short): our message appears, name and email are still filled in,
  only the password boxes are empty.

### 1.6 Sign-up lands on Overview (G7)
- [x] **What:** after sign-up, Overview with the welcome message and a big **Add your first worker** button.
- **How:** redirect to `/overview` in `LoginController`; button in `overview.html`.
- **See it work:** create a new account: you land on Overview and the button opens Staff.

### 1.7 Monthly Pay wording (G9)
- [x] **What:** "1 day" / "2 days"; only one "not recorded" count, and it matches the calendar.
- **How:** plural helper in `pay.html`; show the calendar count only; the long earnings text stays only on a refused close.
- **See it work:** a worker who came once shows "Came 1 day"; the counts on Pay and Calendar are the same number.

### 1.8 Plain "How this was worked out" (G10)
- [x] **What:** no "(standard rate #2)", no "(one day line per date)", dates like "5 Oct".
- **How:** clean the text when it is shown (`EarningsService.Line`); the saved audit text is not changed.
- **See it work:** open "How this was worked out" on Monthly Pay and on a salary slip: plain sentences only.

---

## Part 2. Must-haves still missing from the feature list

### 2.1 Worker-specific holidays
_Built without a new table: a holiday or leave on the worker's profile is saved day by day exactly like one marked on the Today screen, so the calendar and the pay already treat it correctly._
- [x] **What:** give one worker paid days off (for example Sunita's festival leave) without a household holiday.
- **How (as built):** a "Holiday or leave for this person only" form on the worker profile (one day or up to 31 days, `WorkerProfileController`) that records each
  scheduled day through `DailyWorkService.markWholeDay`; no new table was needed.
- **See it work:** on Sunita's profile add a paid holiday on a weekday. Her calendar shows "Holiday" on that day,
  another worker's does not, and her Monthly Pay is not reduced.

### 2.2 Confirm unrecorded days for monthly workers
- [x] **What:** before closing a month, a monthly worker's unrecorded days can be confirmed as "not worked" with one button.
- **How:** button on Monthly Pay that calls the existing `EarningsService.confirmUnrecorded`, with a clear confirmation.
- **See it work:** leave two of Sunita's past days unrecorded; Monthly Pay offers "These days were not worked"; after
  confirming, the month can be closed and the pay reflects it.

### 2.3 Review a line that needs a decision
- [x] **What:** lines marked "needs a decision" can be accepted or set to an amount, with a reason.
- **How:** small form next to such a line, calling the existing `EarningsService.reviewLine`.
- **See it work:** mark a salaried worker "Part day"; the line shows "needs a decision"; choose an amount with a reason;
  the total updates.

### 2.4 Change where an unpaid balance is carried
- [x] **What:** on the month-end screen, move an unpaid balance to a later month.
- **How:** form on `settlement.html` calling the existing `SettlementService.correctCarry`, with a reason.
- **See it work:** close a month with money still to pay; on Month-end choose a later month and save; the later month
  shows the carried amount.

---

## Part 3. Clean-up

- [x] **3.1 Stale lines in RELEASE_CHECKLIST.md:** extra-work form and advance history now DONE; expense-only items
  (budgets, bills, receipts) marked "not needed: Expenses removed". **See it:** the file has no wrong statuses.
- [x] **3.2 README:** remove the recovery code and Expenses; explain the email reset link and the mail settings;
  describe day-by-day workers. **See it:** follow the README on a clean copy and every step matches the app.
- [~] **3.3 All automated tests green** (402 on 2026-10-31); the GitHub build and `clean verify` still to be checked after the push.

---

## Part 4. Final checks in the browser (the agent drives, the owner watches)

Run after Parts 1 to 3. Tick each only when seen working.

- [x] **4.1 M1 Sign-up:** weak password refused with our message and form kept; eye, live match, suggest button work.
- [x] **4.2 M3 Workers:** weekdays only for monthly; per-day and milkman listed every day with no nagging.
- [x] **4.3 M4 Pay:** per-day = days marked × rate; milk = litres × price; monthly correct with holidays and leave.
- [x] **4.4 Corrections:** correct a day, correct a payment, reopen a month: old versions stay visible.
- [x] **4.5 Reports:** salary and payments CSV download; salary slip prints cleanly.
- [~] **4.6 Backup:** (download works; restore through the file chooser could not be tested in the browser pane: do it by hand) download a backup, add a worker, restore: the extra worker is gone and the old records are back.
- [x] **4.7 M5 Delete account:** wrong word and wrong password refused; right ones delete; same email signs up again.
- [x] **4.8 M6 Phone, light and dark:** every page readable, buttons only, Sign out visible.
- [x] **4.9 M7 Expenses gone:** no menu item, no page, no overview card.
- [x] **4.10 Two households:** a second account never sees the first one's workers or pay.

---

## Part 5. Owner steps

- [owner] **5.1 Mail settings (for M2):** set `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
  `SPRING_MAIL_PASSWORD` (for Gmail: an app password), `HOME_EXPENSE_MAIL_FROM`, `HOME_EXPENSE_BASE_URL` as server
  settings, never in chat.
  - [ ] **M2 check:** Forgot password sends an email; the link opens the reset page; the new password works; the same
    link does not work a second time.
- [owner] **5.2 Docker Desktop on (for M8).**
  - [ ] **M8 check:** `docker compose up -d`, sign up, `docker compose restart`, sign in again: the account and
    workers are still there; http://localhost:8080/actuator/health says UP.
- [owner] **5.3 Use it for a real week** with your own maid and milkman; note anything slow or confusing.
  This answers "a new household can set up without help" and "daily attendance is quick to enter".
- [owner] **5.4 Security review** by someone other than the author before real outside users; decide whether
  India's data-protection law (DPDP) applies to you as the host.
- [owner] **5.5 Say "push"**, then decide whether to merge the pull request into `main`.

---

## Done means

All boxes in Parts 1 to 4 ticked, Part 5 checks M2 and M8 ticked, tests and GitHub build green, pushed and merged.
Items planned for later (Hindi, bottom tab bar, PDF library, encryption, overtime, fixed + variable pay,
email verification at sign-up, audit-log screen) stay listed in RELEASE_CHECKLIST.md section E and do not block finishing.

## Order of work

1.2 and 1.4 (CSS) → 1.1 → 1.7 → 1.8 → 1.3 → 1.5 and 1.6 → 2.2 → 2.3 → 2.4 → 2.1 → Part 3 → Part 4 (browser run) →
Part 5 with the owner. One commit per item, tests green each time, nothing pushed without the owner's OK.
