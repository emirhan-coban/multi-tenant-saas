package com.saas.backend.config;

import com.saas.backend.auth.JwtService;
import com.saas.backend.auth.SessionRevocationService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SessionRevocationService sessions;

    public JwtAuthFilter(JwtService jwtService, SessionRevocationService sessions) {
        this.jwtService = jwtService;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        try {
            Claims claims = jwtService.parseAccessToken(token);

            UUID userId = UUID.fromString(claims.getSubject());
            UUID tenantId = UUID.fromString(claims.get("tenantId", String.class));
            String role = claims.get("role", String.class);
                if (sessions.staffTokenRevoked(userId, claims)
                    || !sessions.hasActiveMembership(userId, tenantId)) throw new JwtException("Revoked token");

            AuthenticatedUser principal = new AuthenticatedUser(userId, tenantId, role);

            var authToken = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(() -> "ROLE_" + role)
            );

            SecurityContextHolder.getContext().setAuthentication(authToken);
            TenantContext.set(tenantId);   // ← YENİ
            org.slf4j.MDC.put("tenantId",tenantId.toString());

        } catch (JwtException | IllegalArgumentException ex) {
            // Token bozuk/süresi dolmuş — sessizce geç.
        }

        try {
            filterChain.doFilter(request, response);   // ← try içine alındı
        } finally {
            TenantContext.clear();   // ← YENİ — istek bitince mutlaka temizle
        }
    }
}
