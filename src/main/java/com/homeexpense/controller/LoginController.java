package com.homeexpense.controller;

import com.homeexpense.config.SecurityConfig;
import com.homeexpense.config.AttemptLimiter;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.household.AccountStore;
import com.homeexpense.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Sign in, sign up and password recovery: the pages a visitor can reach without an account. */
@Controller
public class LoginController {

    private final AccountService accounts;
    private final AccountStore store;
    private final SessionSignIn sessions;
    private final AttemptLimiter signUps;
    private final AttemptLimiter recovery;

    public LoginController(AccountService accounts, AccountStore store, SessionSignIn sessions,
                           @Qualifier("signUpLimiter") AttemptLimiter signUps,
                           @Qualifier("recoveryLimiter") AttemptLimiter recovery) {
        this.accounts = accounts;
        this.store = store;
        this.sessions = sessions;
        this.signUps = signUps;
        this.recovery = recovery;
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("page", "");
        return "login";
    }

    @GetMapping("/signup")
    public String signUpForm(Model model) {
        model.addAttribute("page", "");
        return "signup";
    }

    @PostMapping("/signup")
    public String signUp(@RequestParam String name, @RequestParam String email, @RequestParam String password,
                         @RequestParam String confirm, HttpServletRequest request, HttpServletResponse response,
                         RedirectAttributes flash) {
        String address = SecurityConfig.addressKey(request);
        if (signUps.blocked(address)) {
            throw new BusinessRuleException("Too many accounts were made from this address. Please try again in an hour.");
        }
        AccountService.SignedUp created = accounts.signUp(name, email, password, confirm);
        signUps.record(address);
        sessions.signIn(request, response, created.user());
        flash.addFlashAttribute("recoveryCode", AccountService.pretty(created.recoveryCode()));
        return "redirect:/welcome";
    }

    @GetMapping("/forgot")
    public String forgotForm(Model model) {
        model.addAttribute("page", "");
        return "forgot";
    }

    @PostMapping("/forgot")
    public String forgot(@RequestParam String email, @RequestParam String code, @RequestParam String password,
                         @RequestParam String confirm, HttpServletRequest request, HttpServletResponse response,
                         RedirectAttributes flash) {
        String address = SecurityConfig.addressKey(request);
        String who = SecurityConfig.emailKey(email);
        if (recovery.blocked(address) || recovery.blocked(who)) {
            throw new BusinessRuleException("Too many wrong tries. Please wait 10 minutes and try again.");
        }
        AccountService.SignedUp reset;
        try {
            reset = accounts.resetPassword(email, code, password, confirm);
        } catch (BusinessRuleException e) {
            recovery.record(address);
            recovery.record(who);
            throw e;
        }
        recovery.clear(who);
        sessions.signIn(request, response, reset.user());
        flash.addFlashAttribute("recoveryCode", AccountService.pretty(reset.recoveryCode()));
        flash.addFlashAttribute("reset", true);
        return "redirect:/welcome";
    }

    /** Shown once after sign-up or recovery: the new recovery code. */
    @GetMapping("/welcome")
    public String welcome(Model model, Authentication authentication) {
        if (!model.containsAttribute("recoveryCode")) {
            return "redirect:/";
        }
        model.addAttribute("page", "welcome");
        return "welcome";
    }
}
