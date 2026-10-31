package com.homeexpense.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
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
 * Sign-up, password reset by emailed link, and account deletion. Each person who signs up gets a household of
 * their own, with its own empty database. Passwords are kept only as salted hashes; a reset link is kept only as a hash.
 */
@Service
public class AccountService {

    static final int MIN_LENGTH = 9;
    static final int MAX_BYTES = 72;
    static final Duration LINK_LIFETIME = Duration.ofMinutes(30);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final String TAKEN = "That email already has an account. Sign in instead, or use Forgot password.";
    private static final String BAD_LINK = "That reset link is not valid any more. Ask for a new one.";

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

    public synchronized UserRecord signUp(String name, String email, String password, String confirm) {
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
        if (!store.addUser(cleanEmail, cleanName, encoder.encode(password), household)) {
            if (!adopted) {
                store.deleteHousehold(household);
            }
            throw new BusinessRuleException(TAKEN);
        }
        databases.open(household);
        return store.findByEmail(cleanEmail).orElseThrow();
    }

    /**
     * Emails a link that lets the person choose a new password. It works once, for 30 minutes. The caller shows the same
     * answer whether or not the email has an account, so nobody can find out who is registered.
     */
    public void sendResetLink(String email, String baseUrl, MailService mail) {
        if (!mail.enabled()) {
            return;
        }
        UserRecord user = store.findByEmail(cleanEmail(email)).orElse(null);
        if (user == null) {
            return;
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        store.saveResetCode(user.id(), hash(token), Instant.now().plus(LINK_LIFETIME));
        mail.send(user.email(), "Reset your Home Expense Manager password",
                "Hello " + user.name() + ",\n\nTo choose a new password, open this link within 30 minutes:\n\n"
                        + baseUrl + "/reset?token=" + token + "\n\n"
                        + "If you did not ask for this, ignore this email: your password has not changed.\n");
    }

    /** True while the link has not expired and has not been used. */
    public boolean linkIsValid(String token) {
        return findLink(token) != null;
    }

    /** Sets the new password for the person the link was sent to. The link stops working. */
    public UserRecord resetWithLink(String token, String password, String confirm) {
        UserRecord user = findLink(token);
        if (user == null) {
            throw new BusinessRuleException(BAD_LINK);
        }
        checkNewPassword(password, confirm);
        store.updatePassword(user.id(), encoder.encode(password));
        store.deleteResetCode(user.id());
        return store.findById(user.id()).orElseThrow();
    }

    private UserRecord findLink(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return null;
        }
        var saved = store.findResetByHash(hash(token.strip())).orElse(null);
        if (saved == null || saved.expiresAt().isBefore(Instant.now())) {
            return null;
        }
        return store.findById(saved.userId()).orElse(null);
    }

    /** Deletes the account and every record of the household for good. The person must type DELETE and their password. */
    public void deleteAccount(long userId, String password, String typed) {
        UserRecord user = store.findById(userId).orElseThrow(() -> new BusinessRuleException("This account no longer exists."));
        if (typed == null || !"DELETE".equals(typed.strip())) {
            throw new BusinessRuleException("Type the word DELETE in capital letters to confirm.");
        }
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

    /** At least 9 characters with an uppercase letter, a lowercase letter, a digit and a special character. */
    static void checkNewPassword(String password, String confirm) {
        String pw = password == null ? "" : password;
        if (pw.length() < MIN_LENGTH) {
            throw new BusinessRuleException("The password must have at least " + MIN_LENGTH + " characters.");
        }
        if (pw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessRuleException("The password is too long. Use at most 70 letters or digits.");
        }
        boolean upper = pw.chars().anyMatch(Character::isUpperCase);
        boolean lower = pw.chars().anyMatch(Character::isLowerCase);
        boolean digit = pw.chars().anyMatch(Character::isDigit);
        boolean special = pw.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
        if (!(upper && lower && digit && special)) {
            throw new BusinessRuleException("Make the password stronger: it needs an uppercase letter, a lowercase letter, "
                    + "a digit and a special character such as @ # $ %.");
        }
        if (!pw.equals(confirm)) {
            throw new BusinessRuleException("The two passwords are not the same. Please type them again.");
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
