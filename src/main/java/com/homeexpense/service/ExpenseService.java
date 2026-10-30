package com.homeexpense.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;

import com.homeexpense.exception.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Household expenses. An expense is never edited or deleted: a correction voids the entry and records a
 * replacement that points back to it (the database refuses anything else).
 */
@Service
public class ExpenseService {

    public static final List<String> METHODS = List.of("CASH", "UPI", "CARD", "BANK_TRANSFER");

    public record Category(long id, String name) {
    }

    public record Expense(long id, long categoryId, String category, LocalDate spentOn, long amountPaise, String method, String note,
                          boolean recurring, boolean voided, String voidReason, boolean corrected) {
    }

    public record CategoryTotal(String category, long totalPaise) {
    }

    private static final RowMapper<Expense> EXPENSE = (r, i) -> new Expense(r.getLong("id"), r.getLong("category_id"),
            r.getString("category"), LocalDate.parse(r.getString("spent_on")), r.getLong("amount_paise"), r.getString("method"),
            r.getString("note"), r.getInt("recurring") == 1, r.getString("voided_at") != null, r.getString("void_reason"),
            r.getObject("replaces_id") != null);

    private static final String SELECT = "SELECT e.*, c.name AS category FROM expense e JOIN expense_category c ON c.id = e.category_id ";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditService audit;

    public ExpenseService(JdbcTemplate jdbc, Clock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Category> categories() {
        return jdbc.query("SELECT id, name FROM expense_category ORDER BY built_in DESC, name COLLATE NOCASE",
                (r, i) -> new Category(r.getLong("id"), r.getString("name")));
    }

    @Transactional
    public Category addCategory(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 50) {
            throw new BusinessRuleException("Type a category name of up to 50 letters.");
        }
        Long existing = jdbc.query("SELECT id FROM expense_category WHERE name = ? COLLATE NOCASE", (r, i) -> r.getLong(1), clean)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            throw new BusinessRuleException("There is already a category called " + clean + ".");
        }
        jdbc.update("INSERT INTO expense_category (name, built_in) VALUES (?, 0)", clean);
        long id = jdbc.queryForObject("SELECT id FROM expense_category WHERE name = ? COLLATE NOCASE", Long.class, clean);
        return new Category(id, clean);
    }

    @Transactional
    public long record(long categoryId, LocalDate spentOn, long amountPaise, String method, String note, boolean recurring) {
        return insert(categoryId, spentOn, amountPaise, method, note, recurring, null);
    }

    /** Voids the entry and records the corrected one in its place. */
    @Transactional
    public long correct(long id, long categoryId, LocalDate spentOn, long amountPaise, String method, String note, boolean recurring,
                        String reason) {
        Expense original = current(id);
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) {
            throw new BusinessRuleException("Say why you are correcting it.");
        }
        long replacement = insert(categoryId, spentOn, amountPaise, method, note, recurring, original.id());
        jdbc.update("UPDATE expense SET voided_at = ?, void_reason = ? WHERE id = ?", clock.instant().toString(), "Corrected: " + why, id);
        audit.record(null, "EXPENSE", id, "CORRECT", "replacedBy=" + replacement + "; reason=" + why);
        return replacement;
    }

    @Transactional
    public void voidExpense(long id, String reason) {
        current(id);
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty()) {
            throw new BusinessRuleException("Say why you are removing it.");
        }
        jdbc.update("UPDATE expense SET voided_at = ?, void_reason = ? WHERE id = ?", clock.instant().toString(), why, id);
        audit.record(null, "EXPENSE", id, "VOID", "reason=" + why);
    }

    /** The month's entries, newest first. {@code includeVoided} adds corrected and removed ones. */
    @Transactional(readOnly = true)
    public List<Expense> list(YearMonth month, Long categoryId, boolean includeVoided) {
        String sql = SELECT + "WHERE e.spent_on BETWEEN ? AND ? " + (categoryId == null ? "" : "AND e.category_id = ? ")
                + (includeVoided ? "" : "AND e.voided_at IS NULL ") + "ORDER BY e.spent_on DESC, e.id DESC";
        return categoryId == null ? jdbc.query(sql, EXPENSE, month.atDay(1).toString(), month.atEndOfMonth().toString())
                : jdbc.query(sql, EXPENSE, month.atDay(1).toString(), month.atEndOfMonth().toString(), categoryId);
    }

    @Transactional(readOnly = true)
    public List<CategoryTotal> totalsByCategory(YearMonth month) {
        return jdbc.query("SELECT c.name AS category, SUM(e.amount_paise) AS total FROM expense e JOIN expense_category c ON c.id = e.category_id "
                        + "WHERE e.spent_on BETWEEN ? AND ? AND e.voided_at IS NULL GROUP BY c.id ORDER BY total DESC",
                (r, i) -> new CategoryTotal(r.getString("category"), r.getLong("total")),
                month.atDay(1).toString(), month.atEndOfMonth().toString());
    }

    /** Copies last month's recurring expenses into this month if they are not already there. Returns how many were added. */
    @Transactional
    public int addRecurring(YearMonth month) {
        int added = 0;
        for (Expense old : list(month.minusMonths(1), null, false)) {
            if (!old.recurring()) {
                continue;
            }
            LocalDate date = month.atDay(Math.min(old.spentOn().getDayOfMonth(), month.lengthOfMonth()));
            Integer same = jdbc.queryForObject("SELECT COUNT(*) FROM expense WHERE voided_at IS NULL AND recurring = 1 AND category_id = ? "
                            + "AND amount_paise = ? AND IFNULL(note, '') = IFNULL(?, '') AND spent_on BETWEEN ? AND ?", Integer.class,
                    old.categoryId(), old.amountPaise(), old.note(), month.atDay(1).toString(), month.atEndOfMonth().toString());
            if (same != null && same == 0) {
                insert(old.categoryId(), date, old.amountPaise(), old.method(), old.note(), true, null);
                added++;
            }
        }
        return added;
    }

    private Expense current(long id) {
        Expense found = jdbc.query(SELECT + "WHERE e.id = ?", EXPENSE, id).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException("That expense was not found."));
        if (found.voided()) {
            throw new BusinessRuleException("That expense was already corrected or removed. Correct the current version instead.");
        }
        return found;
    }

    private long insert(long categoryId, LocalDate spentOn, long amountPaise, String method, String note, boolean recurring, Long replaces) {
        if (amountPaise <= 0) {
            throw new BusinessRuleException("Enter an amount above zero.");
        }
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(m)) {
            throw new BusinessRuleException("Choose how it was paid.");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM expense_category WHERE id = ?", Integer.class, categoryId);
        if (exists == null || exists == 0) {
            throw new BusinessRuleException("Choose a category.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        jdbc.update("INSERT INTO expense (category_id, spent_on, amount_paise, method, note, recurring, recorded_at, replaces_id) "
                        + "VALUES (?,?,?,?,?,?,?,?)", categoryId, spentOn.toString(), amountPaise, m, cleanNote, recurring ? 1 : 0,
                clock.instant().toString(), replaces);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM expense", Long.class);
        audit.record(null, "EXPENSE", id, replaces == null ? "RECORD" : "RECORD_REPLACEMENT",
                "amount=" + com.homeexpense.money.Money.format(amountPaise) + "; category=" + categoryId + "; date=" + spentOn);
        return id;
    }
}
