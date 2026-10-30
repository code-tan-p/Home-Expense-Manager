package com.homeexpense.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Facts every page needs, such as whether to show the "Sign out" button. */
@ControllerAdvice
public class SharedPageData {

    private final boolean loginEnabled;

    public SharedPageData(@Value("${home-expense.password:}") String password) {
        this.loginEnabled = !password.isBlank();
    }

    @ModelAttribute("loginEnabled")
    public boolean loginEnabled() {
        return loginEnabled;
    }
}
