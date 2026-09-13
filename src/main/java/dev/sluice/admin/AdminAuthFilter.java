package dev.sluice.admin;

import dev.sluice.config.SluiceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards {@code /admin/**} with a single bearer token.
 *
 * <p>Not an identity system -- v1 has no web UI and no operator accounts. An empty
 * token disables the admin API outright rather than leaving it open, because an
 * unauthenticated endpoint that issues virtual keys is worse than no endpoint.
 */
@Component
@Order(1)
public class AdminAuthFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public AdminAuthFilter(SluiceProperties properties) {
        this.expected = properties.admin().token().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (expected.length == 0) {
            deny(response, "the admin API is disabled: set sluice.admin.token to enable it");
            return;
        }
        String header = request.getHeader("authorization");
        String presented = header != null && header.regionMatches(true, 0, "bearer ", 0, 7)
                ? header.substring(7).trim()
                : request.getHeader("x-sluice-admin-token");

        if (presented == null
                || !MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            deny(response, "invalid admin token");
            return;
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"%s\"}}"
                        .formatted(message));
    }
}
