# Home Expense Manager

A simple, offline household staff and pay application for a single household on one
computer. Tell it each day what happened (worked, litres of milk, hours, leave, holiday) and
it works out what each person has earned in the month, exactly to the paisa.

## How to use it

1. **Staff**: add each worker once: job, start date, how they are paid (monthly salary, per
   day, per hour, per litre) and the weekdays they work.
2. **Today**: each day tap **Done** for each worker. For the milkman type the litres and press
   **Save litres**, or press **No delivery**. For an hourly worker type the hours. Use
   **Our holiday**, **On leave** or **Didn't come** when someone did not work. Wrong tap? Press **Undo**.
3. **Monthly Pay**: see what each person earned, what was paid so far and what is still to pay.
   Type the amount and press **Record payment**.
4. **Backup**: download one file with all your records and keep it safe.

## Known limits (version 1)

- One household, one computer, no login: the server only accepts connections from this computer.
- No screens yet for advances, monthly settlements, deduction decisions, or changing a worker's
  pay or weekdays after adding them. The services for these exist and are tested.
- A payment is counted in the month it is dated; it is not matched to a settlement. "Still to pay"
  is earnings minus that month's payments.
- Opening Monthly Pay recalculates the month's draft earnings.
- Restoring a backup is a manual file swap (see the Backup page).

## Status

- Phase 1 (project foundation): Spring Boot application, SQLite connection, Flyway
  migrations, a placeholder home page and automated tests.
- Phase 2 (workers, schedules and rates): database tables, services, validation and
  tests for workers and employment periods, tasks and shifts, effective-dated weekly
  schedules, payment arrangements and pay-rate history, plus an audit log. There are no
  screens for these yet.
- Phase 3 (daily work and attendance): recording what actually happened for each
  scheduled activity (done, partly done, or not done because of a household holiday,
  approved leave or unauthorized absence), unscheduled extra work, duplicate warnings,
  deduction decisions for unauthorized absences, and void-and-replace corrections with
  full history. Backend only; no screens yet, and no earnings are calculated.

- Phase 4 (earnings calculation): monthly earnings per worker for all six payment models,
  calculated from recorded work, effective-dated rates and schedules. Every amount is an
  exact fraction rounded half-up once to the paisa and explained line by line. Partial
  work and amounts without a baseline wait for review; unrecorded scheduled activities
  block finalizing until recorded or explicitly confirmed. Finalized months lock their
  work records, rates and schedules. Backend only; no screens yet.

- Phase 5 (payments and settlements): payments with automatic oldest-first, explicit or
  "keep unallocated" allocation; excess handling; advances and automatic recovery;
  versioned monthly settlements with rounding, carry-forward and overpayment decisions;
  audited reopening of earnings and settlements; corrections by adjustment, reversal or
  reviewed void-and-replace. Financial history is never edited or deleted. Backend only.

- Phase 6 (screens): a Thymeleaf website in an earthy, minimal theme (see
  `src/main/resources/static/css/app.css`) built on the services above. Today, Staff,
  Monthly Pay and Backup. Settlements, advances and corrections have no screens yet.

## Requirements

- JDK 21
- An internet connection the first time you build (to download Maven and dependencies).
  The application itself runs fully offline.

You do not need Maven installed: the included Maven Wrapper (`./mvnw`) downloads the
correct version (3.9.16) automatically.

## Technology

Java 21, Spring Boot 4.1, Spring Data JPA / Hibernate 7.4 with the SQLite community
dialect, SQLite (xerial sqlite-jdbc), Flyway, Thymeleaf, JUnit.

## Build and test

```bash
./mvnw clean verify
```

Integration tests use a real SQLite database in a temporary folder. They never touch
the live household database.

## Run

```bash
./mvnw spring-boot:run
```

Then open <http://127.0.0.1:8080/> in a browser. The server only accepts connections
from this computer (there is no login in version 1).

## Where data is stored

The live database is stored outside this repository:

```
~/.home-expense-manager/home-expense.db
```

The folder is created automatically on first start. SQLite runs in WAL mode, so the
folder may also contain `home-expense.db-wal` and `home-expense.db-shm` files; these are
part of the live database. Do not copy only the `.db` file as a backup. A proper backup
feature is the **Backup** screen: it downloads one complete, consistent copy of the database
(`VACUUM INTO`), so a single file is enough.

To use a different folder (for example, for a trial run):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--home-expense.data-dir=/path/to/folder
```

## Database conventions

- Schema changes are made only through Flyway migrations in
  `src/main/resources/db/migration`. Hibernate validates the schema but never changes it.
- SQLite foreign keys are enforced on every connection.
- Money is stored as whole paise in `INTEGER` columns (Java `long`; ₹1 = 100 paise),
  never as SQLite `REAL`. Calculations use `BigDecimal`. Entered amounts with more than
  two decimal places are rejected, never rounded.
- Dates are stored as ISO text `YYYY-MM-DD`, times as `HH:MM`, timestamps as UTC text,
  days of the week as 1 (Monday) to 7 (Sunday), and booleans as 0/1. JPA converters in
  `model/converter` apply these conventions.
- Effective-dated records (schedules, payment arrangements, pay rates, employment
  periods) never overlap; this is enforced by triggers as well as by the services. A
  change adds a new version instead of editing an old one.
- Expected activities are not stored; they are worked out from the schedules. Work
  records are confirmed facts and are never edited or deleted: a correction voids the
  record and inserts a linked replacement. Database triggers enforce this.
- `HomeExpenseSQLiteDialect` adjusts Hibernate's SQLite dialect so that schema
  validation accepts SQLite `INTEGER` keys for Java `long`, and so that constraint
  failures are reported as data-integrity errors.

Stop the application before running `./mvnw clean verify`: the build deletes the
compiled classes a running application is using, which makes its pages fail.
