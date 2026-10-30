package com.homeexpense.controller;

import com.homeexpense.service.AccountService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Facts every page needs, such as whether to show the "Sign out" button. */
@ControllerAdvice
public class SharedPageData {

    private final AccountService accounts;

    public SharedPageData(AccountService accounts) {
        this.accounts = accounts;
    }

    @ModelAttribute("passwordOn")
    public boolean passwordOn() {
        return accounts.passwordIsOn();
    }
}
