package com.homeexpense.config;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One household password, off by default.
 *
 * <p>With no {@code HOME_EXPENSE_PASSWORD} the program only listens on this computer and has no login, so it
 * is as easy as possible to use. If a password is set, every page except the sign-in page needs it, form posts
 * carry a one-time token (CSRF), and five wrong tries lock sign-in for ten minutes. The program refuses to
 * start if it is told to listen on a network address without a password, so it cannot be opened to others by
 * mistake.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_TIME = Duration.ofMinutes(10);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginThrottle throttle,
                                    @Value("${home-expense.password:}") String password,
                                    @Value("${server.address:127.0.0.1}") String address) throws Exception {
        if (password.isBlank()) {
            requireLocalOnly(address);
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable());
            return http.build();
        }
        AuthenticationFailureHandler failed = (request, response, exception) -> {
            throttle.failed(request.getRemoteAddr());
            response.sendRedirect(request.getContextPath() + "/login?error");
        };
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/css/**", "/login", "/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true).failureHandler(failed).permitAll())
                .logout(l -> l.logoutSuccessUrl("/login?bye").permitAll())
                .addFilterBefore(new ThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(@Value("${home-expense.password:}") String password) {
        // With no password set nobody can sign in: the random value is never shown or used.
        String effective = password.isBlank() ? UUID.randomUUID().toString() : password;
        return new InMemoryUserDetailsManager(User.withUsername("household")
                .password(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(effective))
                .roles("USER").build());
    }

    @Bean
    LoginThrottle loginThrottle(Clock clock) {
        return new LoginThrottle(clock);
    }

    /** Refuses to start when asked to listen beyond this computer with no password. */
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
