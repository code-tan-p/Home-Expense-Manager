package com.homeexpense.controller;

import com.homeexpense.household.AccountStore.UserRecord;
import com.homeexpense.household.HouseholdUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

/** Signs a person in after sign-up or recovery, with a fresh session id so an old session cannot be reused. */
@Component
public class SessionSignIn {

    public void signIn(HttpServletRequest request, HttpServletResponse response, UserRecord record) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        HouseholdUser user = new HouseholdUser(record);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    }
}
