package com.homeexpense.controller;

import java.util.List;

import com.homeexpense.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The very first time the program is opened, every page leads to "Create a password", where the household can
 * also choose to go without one. After either choice this never appears again.
 */
@Configuration
public class FirstRunSetup implements WebMvcConfigurer {

    private static final List<String> OPEN = List.of("/account", "/login", "/css/", "/actuator", "/error");

    private final AccountService accounts;

    public FirstRunSetup(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if ("GET".equals(request.getMethod()) && OPEN.stream().noneMatch(path::startsWith) && accounts.setupPending()) {
                    response.sendRedirect(request.getContextPath() + "/account");
                    return false;
                }
                return true;
            }
        });
    }
}
