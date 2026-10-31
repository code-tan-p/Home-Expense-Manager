package com.homeexpense.config;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import com.homeexpense.household.AccountStore;
import com.homeexpense.household.HouseholdContext;
import com.homeexpense.household.HouseholdUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sign-in for many households. Every page except sign-in, sign-up, password recovery and the style files needs
 * a signed-in person; form posts carry a one-time token (CSRF); and the signed-in person's household is bound
 * to the request so the database layer reaches only that household's records. Five wrong tries for one email
 * or one address lock sign-in for ten minutes.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_TIME = Duration.ofMinutes(10);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, @org.springframework.beans.factory.annotation.Qualifier("loginLimiter") AttemptLimiter limiter,
                                    HouseholdContext households) throws Exception {
        AuthenticationFailureHandler failed = (request, response, exception) -> {
            limiter.record(addressKey(request));
            limiter.record(emailKey(request.getParameter("username")));
            response.sendRedirect(request.getContextPath() + "/login?error");
        };
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico", "/login", "/signup", "/forgot", "/reset",
                                "/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().access((authentication, context) ->
                                new AuthorizationDecision(households.isFixed() || signedIn(authentication.get()))))
                // The automated tests run with one fixed household and no sign-in, so they post without a token.
                .csrf(c -> c.ignoringRequestMatchers(request -> households.isFixed()))
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true).failureHandler(failed).permitAll())
                .logout(l -> l.logoutSuccessUrl("/login?bye").permitAll())
                .addFilterBefore(new ThrottleFilter(limiter), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new HouseholdFilter(households), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(AccountStore accounts) {
        return username -> accounts.findByEmail(username == null ? "" : username.strip().toLowerCase(Locale.ROOT))
                .<org.springframework.security.core.userdetails.UserDetails>map(HouseholdUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("No such account."));
    }

    @Bean(name = "loginLimiter")
    AttemptLimiter loginLimiter(Clock clock) {
        return new AttemptLimiter(clock, MAX_FAILURES, LOCK_TIME);
    }

    @Bean(name = "signUpLimiter")
    AttemptLimiter signUpLimiter(Clock clock) {
        return new AttemptLimiter(clock, 5, Duration.ofHours(1));
    }

    @Bean(name = "recoveryLimiter")
    AttemptLimiter recoveryLimiter(Clock clock) {
        return new AttemptLimiter(clock, MAX_FAILURES, LOCK_TIME);
    }

    public static String addressKey(HttpServletRequest request) {
        return "ip:" + request.getRemoteAddr();
    }

    public static String emailKey(String email) {
        return "email:" + (email == null ? "" : email.strip().toLowerCase(Locale.ROOT));
    }

    private static boolean signedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /** Binds the signed-in person's household to the request, and clears it afterwards. */
    private static final class HouseholdFilter extends OncePerRequestFilter {
        private final HouseholdContext households;

        HouseholdFilter(HouseholdContext households) {
            this.households = households;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof HouseholdUser user) {
                households.bind(user.getHouseholdId());
            }
            try {
                chain.doFilter(request, response);
            } finally {
                households.unbind();
            }
        }
    }

    private static final class ThrottleFilter extends OncePerRequestFilter {
        private final AttemptLimiter limiter;

        ThrottleFilter(AttemptLimiter limiter) {
            this.limiter = limiter;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if ("POST".equals(request.getMethod()) && (request.getContextPath() + "/login").equals(request.getRequestURI())
                    && (limiter.blocked(addressKey(request)) || limiter.blocked(emailKey(request.getParameter("username"))))) {
                response.sendRedirect(request.getContextPath() + "/login?locked");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
