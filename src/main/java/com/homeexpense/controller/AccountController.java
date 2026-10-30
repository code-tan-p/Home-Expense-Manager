package com.homeexpense.controller;

import java.util.List;

import com.homeexpense.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The "Account" screen: create, change or turn off the household password, and sign out. */
@Controller
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/account")
    public String account(Model model) {
        model.addAttribute("page", "account");
        model.addAttribute("on", accounts.passwordIsOn());
        model.addAttribute("serverManaged", accounts.managedByServer());
        model.addAttribute("firstRun", accounts.setupPending());
        return "account";
    }

    /** Creates the password. The person who created it is signed in straight away. */
    @PostMapping("/account/password/new")
    public String create(@RequestParam String password, @RequestParam String confirm, HttpServletRequest request,
                         HttpServletResponse response, RedirectAttributes flash) {
        accounts.setPassword(password, confirm);
        signIn(request, response);
        flash.addFlashAttribute("ok", "Password created. From now on this program asks for it.");
        return "redirect:/";
    }

    @PostMapping("/account/password/change")
    public String change(@RequestParam String current, @RequestParam String password, @RequestParam String confirm,
                         HttpServletRequest request, HttpServletResponse response, RedirectAttributes flash) {
        accounts.changePassword(current, password, confirm);
        signIn(request, response);
        flash.addFlashAttribute("ok", "Password changed.");
        return "redirect:/account";
    }

    @PostMapping("/account/password/off")
    public String off(@RequestParam String current, RedirectAttributes flash) {
        accounts.turnOff(current);
        flash.addFlashAttribute("ok", "Password turned off. Anyone who can open this address can now see the records.");
        return "redirect:/account";
    }

    @PostMapping("/account/skip")
    public String skip(RedirectAttributes flash) {
        accounts.skipSetup();
        flash.addFlashAttribute("ok", "No password for now. You can create one any time on the Account page.");
        return "redirect:/";
    }

    private static void signIn(HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(AccountService.USERNAME, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    }
}
