package com.homeexpense.household;

import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which household the current thread is working for. Every request binds it from the signed-in person,
 * and every background job binds it explicitly. With nothing bound, any database access fails, so a
 * missing binding can never fall through to someone else's records.
 *
 * <p>The setting {@code home-expense.fixed-household} (used by the automated tests) names one household
 * to use when nothing is bound. It is empty in a real installation.
 */
@Component
public class HouseholdContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private final Long fixed;

    public HouseholdContext(@Value("${home-expense.fixed-household:}") String fixed) {
        this.fixed = fixed == null || fixed.isBlank() ? null : Long.valueOf(fixed.strip());
    }

    public boolean isFixed() {
        return fixed != null;
    }

    public void bind(long householdId) {
        CURRENT.set(householdId);
    }

    public void unbind() {
        CURRENT.remove();
    }

    public long current() {
        Long id = CURRENT.get();
        if (id != null) {
            return id;
        }
        if (fixed != null) {
            return fixed;
        }
        throw new IllegalStateException("No household is signed in for this work.");
    }

    public <T> T call(long householdId, Supplier<T> work) {
        Long before = CURRENT.get();
        CURRENT.set(householdId);
        try {
            return work.get();
        } finally {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        }
    }

    public void run(long householdId, Runnable work) {
        call(householdId, () -> {
            work.run();
            return null;
        });
    }
}
