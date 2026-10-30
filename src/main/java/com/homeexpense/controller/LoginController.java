package com.homeexpense.controller;

import com.homeexpense.service.AccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The sign-in page. With no password there is nothing to sign in to, so it leads back to the program. */
@Controller
public class LoginController {

    private final AccountService accounts;

    public LoginController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/login")
    public String login(Model model) {
        if (!accounts.passwordIsOn()) {
            return "redirect:/";
        }
        model.addAttribute("page", "");
        return "login";
    }
}
