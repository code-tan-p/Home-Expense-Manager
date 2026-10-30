package com.homeexpense.controller;

import com.homeexpense.household.HouseholdUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Facts every page needs, such as who is signed in (for the greeting and the "Sign out" button). */
@ControllerAdvice
public class SharedPageData {

    @ModelAttribute("signedInName")
    public String signedInName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof HouseholdUser user ? user.getDisplayName() : null;
    }
}
