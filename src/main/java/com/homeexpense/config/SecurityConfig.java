package com.homeexpense.config;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.homeexpense.service.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The household sign-in.
 *
 * <p>Whether a password is needed is decided on every request, so turning the password on or off on the
 * Account page takes effect at once. With no password the program only listens on this computer and every page
 * is open. With a password, every page except the sign-in page needs it, form posts carry a one-time token
 * (CSRF), and five wrong tries lock sign-in for ten minutes. The program refuses to start on a network address
 * unless the server setting {@code HOME_EXPENSE_PASSWORD} is set.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_TIME = Duration.ofMinutes(10);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginThrottle throttle, AccountService accounts,
                                    @Value("${home-expense.password:}") String serverPassword,
                                    @Value("${server.address:127.0.0.1}") String address) throws Exception {
        if (serverPassword.isBlank()) {
            requireLocalOnly(address);
        }
        AuthenticationFailureHandler failed = (request, response, exception) -> {
            throttle.failed(request.getRemoteAddr());
            response.sendRedirect(request.getContextPath() + "/login?error");
        };
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/css/**", "/login", "/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().access((authentication, context) ->
                                new AuthorizationDecision(!accounts.passwordIsOn() || signedIn(authentication.get()))))
                // While there is no password there is nobody to forge a request against, so forms need no token.
                .csrf(c -> c.ignoringRequestMatchers(request -> !accounts.passwordIsOn()))
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true).failureHandler(failed).permitAll())
                .logout(l -> l.logoutSuccessUrl("/login?bye").permitAll())
                .addFilterBefore(new ThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(AccountService accounts) {
        return username -> {
            String hash = AccountService.USERNAME.equals(username) ? accounts.encodedPassword() : null;
            if (hash == null) {
                throw new UsernameNotFoundException("No password is set.");
            }
            return User.withUsername(AccountService.USERNAME).password(hash).roles("USER").build();
        };
    }

    @Bean
    LoginThrottle loginThrottle(Clock clock) {
        return new LoginThrottle(clock);
    }

    private static boolean signedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /** Refuses to start when asked to listen beyond this computer with no server-set password. */
    static void requireLocalOnly(String address) {
        boolean local;
        try {
            local = address.isBlank() || InetAddress.getByName(address).isLoopbackAddress();
        } catch (IOException e) {
            local = false;
        }
        if (!local) {
            throw new IllegalStateException("The program was told to listen on " + address + " (a network address) but no "
                    + "password is set. Set HOME_EXPENSE_PASSWORD first, or listen only on this computer (127.0.0.1).");
        }
    }

    /** Remembers wrong sign-in tries per address. */
    static final class LoginThrottle {
        private final Clock clock;
        private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

        LoginThrottle(Clock clock) {
            this.clock = clock;
        }

        void failed(String address) {
            Deque<Instant> recent = failures.computeIfAbsent(address, a -> new ArrayDeque<>());
            synchronized (recent) {
                recent.addLast(clock.instant());
                trim(recent);
            }
        }

        boolean locked(String address) {
            Deque<Instant> recent = failures.get(address);
            if (recent == null) {
                return false;
            }
            synchronized (recent) {
                trim(recent);
                return recent.size() >= MAX_FAILURES;
            }
        }

        private void trim(Deque<Instant> recent) {
            Instant cutoff = clock.instant().minus(LOCK_TIME);
            while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
                recent.removeFirst();
            }
        }
    }

    private static final class ThrottleFilter extends OncePerRequestFilter {
        private final LoginThrottle throttle;

        ThrottleFilter(LoginThrottle throttle) {
            this.throttle = throttle;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if ("POST".equals(request.getMethod()) && (request.getContextPath() + "/login").equals(request.getRequestURI())
                    && throttle.locked(request.getRemoteAddr())) {
                response.sendRedirect(request.getContextPath() + "/login?locked");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
