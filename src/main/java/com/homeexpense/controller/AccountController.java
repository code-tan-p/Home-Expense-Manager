package com.homeexpense.controller;

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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The "Account" screen: your details, change password, delete everything. */
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
        model.addAttribute("canChange", user != null);
        return "account";
    }

    @PostMapping("/account/password/change")
    public String change(@RequestParam String current, @RequestParam String password, @RequestParam String confirm,
                         Authentication authentication, RedirectAttributes flash) {
        accounts.changePassword(required(authentication).getUserId(), current, password, confirm);
        flash.addFlashAttribute("ok", "Password changed.");
        return "redirect:/account";
    }

    @PostMapping("/account/delete")
    public String delete(@RequestParam String password, @RequestParam(defaultValue = "") String sure,
                         Authentication authentication, HttpServletRequest request, RedirectAttributes flash) {
        if (sure.isBlank()) {
            flash.addFlashAttribute("error", "Tick the box to confirm that you want to delete everything.");
            return "redirect:/account";
        }
        accounts.deleteAccount(required(authentication).getUserId(), password);
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
        return "redirect:/login?deleted";
    }

    private static HouseholdUser user(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof HouseholdUser u ? u : null;
    }

    private static HouseholdUser required(Authentication authentication) {
        HouseholdUser user = user(authentication);
        if (user == null) {
            throw new com.homeexpense.exception.BusinessRuleException("Please sign in first.");
        }
        return user;
    }
}
