package com.saas.backend.config;

import com.saas.backend.auth.JwtService;
import com.saas.backend.customer.CustomerPrincipal;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class CustomerJwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;

    public CustomerJwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/customer/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (SecurityContextHolder.getContext().getAuthentication() == null
                && header != null && header.startsWith("Bearer ")) {
            try {
                var claims = jwtService.parseCustomerAccessToken(header.substring(7));
                var principal = new CustomerPrincipal(UUID.fromString(claims.getSubject()));
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(() -> "ROLE_CUSTOMER"));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                // Spring Security returns the stable 401 response below the filter chain.
            }
        }
        chain.doFilter(request, response);
    }
}
