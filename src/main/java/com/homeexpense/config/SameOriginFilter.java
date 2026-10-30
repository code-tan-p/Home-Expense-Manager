package com.homeexpense.config;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a form post that was started from another website (cross-site request forgery).
 *
 * <p>There is no login, so the only protection against a page in another tab posting to
 * {@code localhost:8080} is to check where the post came from. Browsers always send an
 * {@code Origin} (or at least a {@code Referer}) with a cross-site post; it must name this same
 * host. Posts with neither header, such as from a command line, are allowed.
 */
@Component
public class SameOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod()) || cameFromThisSite(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "This request did not come from this website.");
    }

    static boolean cameFromThisSite(HttpServletRequest request) {
        String source = request.getHeader("Origin");
        if ("null".equals(source)) {
            // Sent by sandboxed or redirected cross-site pages. Never by this site's own forms.
            return false;
        }
        if (source == null || source.isBlank()) {
            source = request.getHeader("Referer");
        }
        if (source == null || source.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(source);
            String host = request.getHeader("Host");
            if (host == null) {
                host = request.getServerPort() == 80 || request.getServerPort() == 443
                        ? request.getServerName() : request.getServerName() + ":" + request.getServerPort();
            }
            String sourceHost = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return host != null && host.equalsIgnoreCase(sourceHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
