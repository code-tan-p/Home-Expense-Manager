package com.homeexpense.household;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** The signed-in person: who they are and which household's records they may use. */
public class HouseholdUser extends User {

    private static final long serialVersionUID = 1L;

    private final long userId;
    private final long householdId;
    private final String displayName;

    public HouseholdUser(AccountStore.UserRecord record) {
        super(record.email(), record.passwordHash(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.userId = record.id();
        this.householdId = record.householdId();
        this.displayName = record.name();
    }

    public long getUserId() {
        return userId;
    }

    public long getHouseholdId() {
        return householdId;
    }

    public String getDisplayName() {
        return displayName;
    }
}
