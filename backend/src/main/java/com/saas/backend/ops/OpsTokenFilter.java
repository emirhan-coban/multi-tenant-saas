package com.saas.backend.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * {@code /actuator/**} (health hariç) yalnızca operasyon token'ı ile açılır. Token tanımlı değilse metrik uçları
 * hiç açılmaz. Kullanıcı/müşteri JWT'leri bu uçlara erişim vermez.
 */
public class OpsTokenFilter extends OncePerRequestFilter {
    private final byte[] token;

    public OpsTokenFilter(String token) {
        this.token = token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Kullanıcı JWT filtresi bir kimlik koymuş olsa bile actuator için yalnızca OPS kimliği geçerlidir.
        SecurityContextHolder.clearContext();
        String header = request.getHeader("Authorization");
        if (token != null && header != null && header.startsWith("Bearer ")
                && MessageDigest.isEqual(token, header.substring(7).trim().getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("ops", null, List.of(() -> "ROLE_OPS")));
        }
        chain.doFilter(request, response);
    }
}
