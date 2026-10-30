package com.homeexpense.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.household.AccountStore;
import com.homeexpense.household.AccountStore.UserRecord;
import com.homeexpense.household.HouseholdDatabases;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Sign-up, password changes, recovery and account deletion. Each person who signs up gets a household of
 * their own, with its own empty database. Passwords and recovery codes are kept only as salted hashes.
 */
@Service
public class AccountService {

    static final int MIN_LENGTH = 8;
    static final int MAX_BYTES = 72;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final String CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String TAKEN = "That email already has an account. Sign in instead, or use Forgot password.";

    /** What a new account is told once: the recovery code is never shown again. */
    public record SignedUp(UserRecord user, String recoveryCode) {
    }

    private final AccountStore store;
    private final HouseholdDatabases databases;
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final int maxHouseholds;

    public AccountService(AccountStore store, HouseholdDatabases databases,
                          @Value("${home-expense.max-households:500}") int maxHouseholds) {
        this.store = store;
        this.databases = databases;
        this.maxHouseholds = maxHouseholds;
    }

    public synchronized SignedUp signUp(String name, String email, String password, String confirm) {
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isEmpty() || cleanName.length() > 60) {
            throw new BusinessRuleException("Please type your name (up to 60 letters).");
        }
        String cleanEmail = cleanEmail(email);
        checkNewPassword(password, confirm);
        if (store.findByEmail(cleanEmail).isPresent()) {
            throw new BusinessRuleException(TAKEN);
        }
        // Records from the one-household version wait here for the first person to sign up.
        long household = store.unclaimedHousehold().orElse(0L);
        boolean adopted = household != 0;
        if (!adopted) {
            if (store.householdCount() >= maxHouseholds) {
                throw new BusinessRuleException("Sign-up is full on this server right now. Please ask the person who runs it.");
            }
            household = store.createHousehold();
        }
        String code = newRecoveryCode();
        if (!store.addUser(cleanEmail, cleanName, encoder.encode(password), encoder.encode(code), household)) {
            if (!adopted) {
                store.deleteHousehold(household);
            }
            throw new BusinessRuleException(TAKEN);
        }
        databases.open(household);
        return new SignedUp(store.findByEmail(cleanEmail).orElseThrow(), code);
    }

    public void changePassword(long userId, String current, String password, String confirm) {
        UserRecord user = store.findById(userId).orElseThrow(() -> new BusinessRuleException("This account no longer exists."));
        if (current == null || !encoder.matches(current, user.passwordHash())) {
            throw new BusinessRuleException("The current password is not right.");
        }
        checkNewPassword(password, confirm);
        store.updatePassword(userId, encoder.encode(password));
    }

    /** Sets a new password for someone who kept their recovery code; returns the new recovery code. */
    public SignedUp resetPassword(String email, String code, String password, String confirm) {
        UserRecord user = store.findByEmail(cleanEmail(email)).orElse(null);
        String given = code == null ? "" : code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (user == null || !encoder.matches(given, user.recoveryHash())) {
            throw new BusinessRuleException("That email and recovery code do not match.");
        }
        checkNewPassword(password, confirm);
        String fresh = newRecoveryCode();
        store.updatePassword(user.id(), encoder.encode(password));
        store.updateRecovery(user.id(), encoder.encode(fresh));
        return new SignedUp(store.findById(user.id()).orElseThrow(), fresh);
    }

    /**
     * Emails a six-digit code that works for fifteen minutes. Always looks the same to the caller whether or not the
     * email has an account, so nobody can find out who is registered.
     */
    public void sendResetCode(String email, MailService mail) {
        if (!mail.enabled()) {
            return;
        }
        UserRecord user = store.findByEmail(cleanEmail(email)).orElse(null);
        if (user == null) {
            return;
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        store.saveResetCode(user.id(), encoder.encode(code), java.time.Instant.now().plus(java.time.Duration.ofMinutes(15)));
        mail.send(user.email(), "Your Home Expense Manager code",
                "Hello " + user.name() + ",\n\nYour code is " + code + ". It works for 15 minutes.\n"
                        + "If you did not ask for it, ignore this email: your password has not changed.\n");
    }

    /** Sets a new password for someone who typed the emailed code. */
    public UserRecord resetWithEmailCode(String email, String code, String password, String confirm) {
        String failure = "That code is wrong or has expired. Ask for a new one.";
        UserRecord user = store.findByEmail(cleanEmail(email)).orElse(null);
        var saved = user == null ? null : store.findResetCode(user.id()).orElse(null);
        if (saved == null || saved.tries() >= 5 || saved.expiresAt().isBefore(java.time.Instant.now())) {
            throw new BusinessRuleException(failure);
        }
        String given = code == null ? "" : code.replace(" ", "");
        if (!encoder.matches(given, saved.codeHash())) {
            store.countResetTry(user.id());
            throw new BusinessRuleException(failure);
        }
        checkNewPassword(password, confirm);
        store.updatePassword(user.id(), encoder.encode(password));
        store.deleteResetCode(user.id());
        return store.findById(user.id()).orElseThrow();
    }

    /** Deletes the account and every record of the household for good. */
    public void deleteAccount(long userId, String password) {
        UserRecord user = store.findById(userId).orElseThrow(() -> new BusinessRuleException("This account no longer exists."));
        if (password == null || !encoder.matches(password, user.passwordHash())) {
            throw new BusinessRuleException("The password is not right, so nothing was deleted.");
        }
        try {
            databases.delete(user.householdId());
        } catch (IOException e) {
            throw new BusinessRuleException("The records could not be deleted. Close other programs using them and try again.");
        }
        store.deleteHousehold(user.householdId());
    }

    private static String cleanEmail(String email) {
        String clean = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (clean.length() > 120 || !EMAIL.matcher(clean).matches()) {
            throw new BusinessRuleException("Please type a valid email address, like name@example.com.");
        }
        return clean;
    }

    private static void checkNewPassword(String password, String confirm) {
        String pw = password == null ? "" : password;
        if (pw.length() < MIN_LENGTH) {
            throw new BusinessRuleException("The password must have at least " + MIN_LENGTH + " characters.");
        }
        if (pw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessRuleException("The password is too long. Use at most 70 letters or digits.");
        }
        if (!pw.equals(confirm)) {
            throw new BusinessRuleException("The two passwords are not the same. Please type them again.");
        }
    }

    private String newRecoveryCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            code.append(CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
        }
        return code.toString();
    }

    /** The code as shown to people: ABCD-EFGH-JKLM-NPQR. */
    public static String pretty(String code) {
        return code.replaceAll("(.{4})(?!$)", "$1-");
    }
}
