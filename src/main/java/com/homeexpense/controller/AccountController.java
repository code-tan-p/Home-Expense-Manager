package com.homeexpense.controller;

import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.household.HouseholdUser;
import com.homeexpense.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The "Account" screen: who you are, your data, and deleting everything. Passwords are changed only through Forgot password. */
@Controller
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/account")
    public String account(Model model, Authentication authentication) {
        model.addAttribute("page", "account");
        HouseholdUser user = user(authentication);
        model.addAttribute("accountName", user == null ? "" : user.getDisplayName());
        model.addAttribute("accountEmail", user == null ? "" : user.getUsername());
        model.addAttribute("canDelete", user != null);
        return "account";
    }

    @PostMapping("/account/delete")
    public String delete(@RequestParam String password, @RequestParam(defaultValue = "") String typed,
                         Authentication authentication, HttpServletRequest request) {
        HouseholdUser user = user(authentication);
        if (user == null) {
            throw new BusinessRuleException("Please sign in first.");
        }
        accounts.deleteAccount(user.getUserId(), password, typed);
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
        return "redirect:/login?deleted";
    }

    private static HouseholdUser user(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof HouseholdUser u ? u : null;
    }
}
