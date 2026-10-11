# Home-Expense-Manager — Project Instructions

## 1. Project Goal

Build a simple, reliable household expense and worker management application for a single household.

The app must help the user manage household workers, daily work, attendance, salaries, advances, payments, outstanding balances, monthly settlements, reports, and backups.

The application is intended for a single computer and must work completely offline during normal use.

Prioritize correctness of financial calculations, preservation of history, ease of use, and reliable backups.

## 2. Technology Stack

- Java 21
- Spring Boot
- Spring Data JPA
- Hibernate
- SQLite
- Maven with Maven Wrapper
- Flyway for versioned database migrations
- Thymeleaf for server-rendered HTML
- HTML, CSS, and JavaScript
- JUnit and Spring Boot testing tools

Verify dependency compatibility before adding or upgrading versions. Prefer compatible, maintained stable releases. Do not assume that MySQL-specific configuration or SQL works with SQLite.

Use the Maven Wrapper for project builds and tests.

## 3. Application Constraints

- One household.
- One local computer.
- Fully offline during normal operation.
- No login or user-account system in version 1.
- Bind the web server to 127.0.0.1.
- Keep the live SQLite database outside the Git repository, under the user's home directory.
- Create the database directory safely when the application starts.
- Do not introduce cloud services, remote APIs, or mandatory internet dependencies at runtime.

An internet connection may be required initially to download development dependencies.

## 4. Worker Management

Support household workers such as maids, cooks, milkmen, drivers, cleaners, gardeners, and custom worker types.

Workers must support employment dates, schedules, tasks, shifts, payment configurations, rate history, advances, earnings, payments, and active/inactive status.

Deactivating a worker must preserve all historical records. Reactivating a worker must not duplicate records.

Support effective-dated employment and schedule changes.

## 5. Payment Models

Each worker has ONE primary payment model, with optional additional earning rules.

Primary models:
- Monthly salary
- Per-task
- Hourly
- Per-unit, such as milk per litre
- Per-day
- Per-shift

Additional rules may pay separately agreed tasks, extra hours, or extra days.

Prevent accidental double-counting when multiple rules could apply to the same activity. Make the applicable rule and calculation basis explicit.

## 6. Rates and Historical Records

Support standard rates and task/shift-specific rates where required.

Rate changes apply to future entries only. Historical earnings must retain the rate and calculation basis that applied when the work occurred.

Schedule changes must have effective dates. A new schedule must not overwrite historical schedules.

Do not automatically change an agreed monthly salary solely because a schedule changes.

## 7. Daily Work and Attendance

When a user opens a date, display expected activities based on the applicable worker schedules.

Expected activities are not actual work records and must not generate earnings until confirmed.

Support:
- Completed work
- Partial work
- Multiple tasks and shifts per day
- Household-given holidays
- Approved leave
- Unauthorized absence
- Extra work on normal days off
- Quantity-based deliveries
- Missed deliveries

Reopening a date must not create duplicate records. Warn about likely duplicate entries but allow legitimate multiple tasks, shifts, and deliveries.

## 8. Attendance and Leave Rules

- Household-given holidays are paid by default when they fall on scheduled working days.
- A household holiday on a regular day off does not create extra payment.
- Work on a normal day off is extra work with a configurable rate, without double-counting it against monthly salary.
- Worker-requested approved leave is unpaid by default.
- Unauthorized absence is unpaid by default, but a deduction requires explicit user approval.
- An unresolved unauthorized absence may remain flagged during settlement. Allow settlement with a warning and do not apply an unapproved deduction.

For partial work, compare actual hours against usual scheduled hours. Suggest a proportional amount and allow the user to review or override it.

## 9. Partial-Month Salary

Prorate monthly salary when employment starts or ends partway through a month, using applicable scheduled working days during the employment period.

Record the calculation basis and allow the user to review the suggested amount.

Do not round intermediate daily salary calculations to whole rupees.

## 10. Financial Precision

Use Java BigDecimal for financial calculations.

SQLite does not provide a conventional exact fixed-precision decimal storage type. Persist monetary transaction amounts as integer paise using Java long, with explicit conversion and rounding rules.

One rupee equals 100 paise. Avoid SQLite REAL/FLOAT storage for money.

Display monetary amounts to exactly two decimal places, such as ₹115.38 and ₹100.00.

Keep intermediate calculation precision until the relevant monetary amount must be recorded. Apply settlement rounding only when the user explicitly selects it.

Supported settlement rounding:
- Exact amount by default
- Round up to the nearest rupee
- Round down to the nearest rupee
- Round to the nearest rupee

Record rounding adjustments separately and allow them to be undone before finalization.

## 11. Advances

Track each advance and its unrecovered balance separately.

Recover advances first from eligible earnings, including eligible extra-work earnings. Then apply remaining eligible earnings to the oldest unpaid earnings.

Recovery must never exceed the eligible amount available.

If no eligible earnings exist, keep the advance outstanding until eligible earnings become available.

Do not create negative salary payments. Never recover the same advance twice.

Keep the recovery linked to the advance and the applicable earnings or settlement.

## 12. Payments and Allocations

A payment transaction is different from its allocation to a salary month or outstanding balance.

Support multiple partial payments. The user chooses which balance or month each payment covers.
If the user does not choose, a payment is allocated automatically to the oldest outstanding month(s) when it is recorded; payments recorded as early payments, or when nothing is outstanding, stay unallocated until the user allocates them.

If a payment exceeds the selected balance:
- Allocate only the amount appropriate to that balance.
- Keep the excess unallocated.
- Ask the user what the excess represents.
- Never silently apply the excess elsewhere or automatically treat it as an advance.

Do not automatically carry forward unpaid balances or mark them settled without the required user decision.

## 13. Settlements and Corrections

Monthly summaries must show earnings, deductions, advance recoveries, payments, adjustments, unallocated amounts, and outstanding balances.

Support settlement statuses such as OPEN, SETTLED, and REOPENED where appropriate.

Allow a settled month to be explicitly reopened while preserving its audit history.

After a payment has been made, corrections must preserve the original payment and create a separate financial adjustment with an amount, reason, date, and affected settlement.

Reopening or correcting a settlement must not duplicate payments or advance recoveries.

## 14. Database Design and Integrity

Use Flyway for versioned schema migrations.

Use Hibernate schema validation rather than allowing Hibernate to create or modify the production schema automatically.

Enable SQLite foreign-key enforcement on every connection.

Design tables, constraints, indexes, and relationships to support:
- Workers and employment periods
- Effective-dated schedules
- Tasks and shifts
- Primary payment configurations and additional earning rules
- Historical rates
- Expected activities and actual work records
- Calculated earnings
- Advances and recoveries
- Payments and payment allocations
- Monthly settlements and settlement items
- Financial adjustments
- Audit history
- Application settings

Keep expected activities separate from confirmed work records.

Use database constraints and transactional service logic to prevent invalid references, duplicate recoveries, and inconsistent allocations.

Do not use destructive schema changes that silently discard household financial history.

## 15. User Interface

The app should be simple for users who prefer minimal typing.

Use:
- Large readable text and buttons
- Plain language
- Icons with text labels
- Clear status indicators
- Quick daily work entry
- Confirmation for consequential actions
- Understandable error messages
- Clear correction history
- Reviewable calculation details

Primary sections:
1. Dashboard
2. Daily activity
3. Workers
4. Calendar and history
5. Payments and advances
6. Monthly summary and settlement
7. Backup, restore, and export

## 16. Backup, Restore, and Reporting

Implement full database backup and restore with validation and confirmation.

Keep timestamped backups separate from the live database.

Ensure SQLite backups are consistent, including when write-ahead logging is enabled. Do not assume copying only the main database file is always sufficient.

Provide appropriate CSV/Excel exports and PDF reports. An export is not a substitute for a complete database backup.

Test backup restoration before declaring the feature complete.

## 17. Testing Requirements

Write automated tests for:
- Database connectivity and migrations
- Worker creation and deactivation
- Schedule and rate history
- Partial-month salary calculations
- Partial work, leave, holidays, and unauthorized absence
- All payment models
- Quantity-based work
- Advance recovery and outstanding balances
- Payment allocation and unallocated excess
- Partial payments
- Settlement rounding and reopening
- Financial adjustments and audit history
- Duplicate prevention
- Backup and restore

Test financial edge cases and use a real SQLite database for integration tests.

## 18. Development Workflow

Implement the application in small phases:

1. Project foundation, dependency compatibility, SQLite connection, migrations, and tests.
2. Worker management, employment periods, schedules, tasks, shifts, and rate history.
3. Daily activity and attendance recording.
4. Earnings calculation engine and financial calculation tests.
5. Advances, payments, allocations, settlements, and corrections.
6. Dashboard, daily-entry interface, calendar, and reports.
7. Backup, restore, and exports.
8. Integration testing, reliability checks, documentation, and packaging preparation.

Do not implement all phases in one pass.

Before each phase:
1. Explain the intended changes.
2. Identify affected files and schema changes.
3. Identify relevant business rules and edge cases.

After each phase:
1. Run the relevant automated tests.
2. Fix failures before proceeding.
3. Summarize files changed and behavior implemented.
4. Report exact build/test commands and results.
5. Wait for approval before beginning the next phase.

## 19. Instructions for Claude Code

- Read this file before making changes.
- Treat these rules as project requirements.
- If a requirement is ambiguous, explain the ambiguity and propose a safe default before implementing it.
- Do not silently invent business rules.
- Do not commit changes unless explicitly asked.
- Do not delete or overwrite existing user work without approval.
- Keep implementation understandable and maintainable.
- Prefer small changes over large rewrites.
- Explain commands that require the user to run them.
- Never claim tests passed unless they were actually executed and passed.
- Do not declare a feature complete solely because its code compiles.
- Keep documentation updated as requirements and implementation evolve.
