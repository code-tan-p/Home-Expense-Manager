package com.homeexpense.controller;

import com.homeexpense.config.AttemptLimiter;
import com.homeexpense.config.SecurityConfig;
import com.homeexpense.exception.BusinessRuleException;
import com.homeexpense.household.AccountStore.UserRecord;
import com.homeexpense.service.AccountService;
import com.homeexpense.service.MailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Sign in, sign up and password reset by emailed link: the pages a visitor can reach without an account. */
@Controller
public class LoginController {

    private final AccountService accounts;
    private final SessionSignIn sessions;
    private final AttemptLimiter signUps;
    private final AttemptLimiter recovery;
    private final MailService mail;
    private final String baseUrl;

    public LoginController(AccountService accounts, SessionSignIn sessions,
                           @Qualifier("signUpLimiter") AttemptLimiter signUps,
                           @Qualifier("recoveryLimiter") AttemptLimiter recovery, MailService mail,
                           @Value("${home-expense.base-url:}") String baseUrl) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.signUps = signUps;
        this.recovery = recovery;
        this.mail = mail;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
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
                         RedirectAttributes flash, Model model) {
        String address = SecurityConfig.addressKey(request);
        UserRecord created;
        try {
            if (signUps.blocked(address)) {
                throw new BusinessRuleException("Too many accounts were made from this address. Please try again in an hour.");
            }
            created = accounts.signUp(name, email, password, confirm);
        } catch (BusinessRuleException e) {
            // Show the same page again with the message, keeping the name and email that were typed (never the passwords).
            model.addAttribute("page", "");
            model.addAttribute("error", e.getMessage());
            model.addAttribute("keepName", name);
            model.addAttribute("keepEmail", email);
            return "signup";
        }
        signUps.record(address);
        sessions.signIn(request, response, created);
        flash.addFlashAttribute("ok", "Welcome, " + created.name() + "! Your account is ready. Add the people who work for you to begin.");
        return "redirect:/overview";
    }

    @GetMapping("/forgot")
    public String forgotForm(Model model) {
        model.addAttribute("page", "");
        model.addAttribute("mailEnabled", mail.enabled());
        return "forgot";
    }

    /** Emails a reset link. The answer is the same whether or not the email has an account. */
    @PostMapping("/forgot")
    public String forgot(@RequestParam String email, HttpServletRequest request, RedirectAttributes flash) {
        String address = SecurityConfig.addressKey(request);
        String who = SecurityConfig.emailKey(email);
        if (recovery.blocked(address) || recovery.blocked(who)) {
            throw new BusinessRuleException("Too many tries. Please wait 10 minutes and try again.");
        }
        recovery.record(address);
        recovery.record(who);
        accounts.sendResetLink(email, baseUrl.isEmpty() ? requestBase(request) : baseUrl, mail);
        flash.addFlashAttribute("ok", "If that email has an account, we have sent a link to choose a new password. It works for 30 minutes.");
        return "redirect:/forgot";
    }

    @GetMapping("/reset")
    public String resetForm(@RequestParam(defaultValue = "") String token, Model model) {
        model.addAttribute("page", "");
        model.addAttribute("token", token);
        model.addAttribute("valid", accounts.linkIsValid(token));
        return "reset";
    }

    @PostMapping("/reset")
    public String reset(@RequestParam String token, @RequestParam String password, @RequestParam String confirm,
                        HttpServletRequest request, HttpServletResponse response, RedirectAttributes flash) {
        String address = SecurityConfig.addressKey(request);
        if (recovery.blocked(address)) {
            throw new BusinessRuleException("Too many tries. Please wait 10 minutes and try again.");
        }
        UserRecord user;
        try {
            user = accounts.resetWithLink(token, password, confirm);
        } catch (BusinessRuleException e) {
            recovery.record(address);
            throw e;
        }
        sessions.signIn(request, response, user);
        flash.addFlashAttribute("ok", "Your password is changed and you are signed in.");
        return "redirect:/";
    }

    /** The address people used to reach the site, for the link in the email, when no fixed address is configured. */
    private static String requestBase(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean standard = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (standard ? "" : ":" + port);
    }
}
