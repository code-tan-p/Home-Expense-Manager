package com.homeexpense.service;

import java.util.concurrent.atomic.AtomicReference;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.model.AppSetting;
import com.homeexpense.repository.AppSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The household password.
 *
 * <p>There is one shared account. The password is stored as a salted hash in the database (never as text), or
 * comes from the {@code HOME_EXPENSE_PASSWORD} server setting, which wins and cannot be changed on screen.
 * Until a password is set or the first-run step is skipped, the program asks the household to choose.
 */
@Service
public class AccountService {

    public static final String USERNAME = "household";
    static final String HASH = "account.password_hash";
    static final String SKIPPED = "account.setup_skipped";
    static final int MIN_LENGTH = 6;
    static final int MAX_LENGTH = 100;

    private final AppSettingRepository settings;
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final String serverPassword;
    private final boolean firstRunSetup;
    private final AtomicReference<Boolean> passwordOn = new AtomicReference<>();

    public AccountService(AppSettingRepository settings, @Value("${home-expense.password:}") String serverPassword,
                          @Value("${home-expense.first-run-setup:true}") boolean firstRunSetup) {
        this.settings = settings;
        this.serverPassword = serverPassword == null ? "" : serverPassword;
        this.firstRunSetup = firstRunSetup;
    }

    /** True when the password comes from the server setting and cannot be changed here. */
    public boolean managedByServer() {
        return !serverPassword.isBlank();
    }

    public boolean passwordIsOn() {
        if (managedByServer()) {
            return true;
        }
        Boolean known = passwordOn.get();
        if (known == null) {
            known = settings.existsById(HASH);
            passwordOn.set(known);
        }
        return known;
    }

    /** True until the household has set a password or chosen to go without one. */
    public boolean setupPending() {
        return firstRunSetup && !passwordIsOn() && !settings.existsById(SKIPPED);
    }

    /** The stored (or server-set) password as a hash, or null when there is no password. */
    public String encodedPassword() {
        if (managedByServer()) {
            return encoder.encode(serverPassword);
        }
        return settings.findById(HASH).map(AppSetting::getValue).orElse(null);
    }

    @Transactional
    public void setPassword(String password, String confirm) {
        requireChangeable();
        if (passwordIsOn()) {
            throw new BusinessRuleException("A password is already set. Use 'Change password' instead.");
        }
        store(password, confirm);
    }

    @Transactional
    public void changePassword(String current, String password, String confirm) {
        requireChangeable();
        requireCurrent(current);
        store(password, confirm);
    }

    @Transactional
    public void turnOff(String current) {
        requireChangeable();
        requireCurrent(current);
        settings.deleteById(HASH);
        settings.save(new AppSetting(SKIPPED, "true"));
        passwordOn.set(false);
    }

    @Transactional
    public void skipSetup() {
        settings.save(new AppSetting(SKIPPED, "true"));
    }

    private void store(String password, String confirm) {
        String pw = password == null ? "" : password;
        if (pw.length() < MIN_LENGTH) {
            throw new BusinessRuleException("The password must have at least " + MIN_LENGTH + " characters.");
        }
        if (pw.length() > MAX_LENGTH) {
            throw new BusinessRuleException("The password is too long. Use at most " + MAX_LENGTH + " characters.");
        }
        if (!pw.equals(confirm)) {
            throw new BusinessRuleException("The two passwords are not the same. Please type them again.");
        }
        settings.save(new AppSetting(HASH, encoder.encode(pw)));
        settings.deleteById(SKIPPED);
        passwordOn.set(true);
    }

    private void requireCurrent(String current) {
        String hash = encodedPassword();
        if (hash == null || current == null || !encoder.matches(current, hash)) {
            throw new BusinessRuleException("The current password is not right.");
        }
    }

    private void requireChangeable() {
        if (managedByServer()) {
            throw new BusinessRuleException("The password is set by the server (HOME_EXPENSE_PASSWORD) and cannot be changed here.");
        }
    }
}
