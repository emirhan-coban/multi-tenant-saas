package com.saas.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.http.MediaType;
import java.util.List;

@Configuration
@EnableMethodSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CustomerJwtAuthFilter customerJwtAuthFilter;
    private final SecurityProperties security;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, CustomerJwtAuthFilter customerJwtAuthFilter, SecurityProperties security) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.customerJwtAuthFilter = customerJwtAuthFilter;
        this.security = security;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, com.saas.backend.operations.TenantRateLimiter limiter,
                                           com.saas.backend.operations.OperationsService operations,
                                           com.saas.backend.ops.OpsMetrics metrics,
                                           @org.springframework.beans.factory.annotation.Value("${app.ops.metrics-token:}") String opsToken) throws Exception {
        http
                // Faz 9: joker kaynak yok; yalnızca app.security.allowed-origins listesi.
                .cors(cors -> cors.configurationSource(request -> {
                    var config = new CorsConfiguration();
                    config.setAllowedOrigins(security.allowedOrigins());
                    config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
                    config.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key","X-Ops-Token","X-Trace-Id"));
                    config.setExposedHeaders(List.of("Retry-After","X-Trace-Id","X-RateLimit-Remaining"));
                    config.setAllowCredentials(false);
                    config.setMaxAge(1800L);
                    return config;
                }))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(options -> {})
                        .referrerPolicy(policy -> policy.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(security.contentSecurityPolicy()))
                        .crossOriginOpenerPolicy(coop -> coop.policy(
                                org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        .httpStrictTransportSecurity(hsts -> {
                            if (security.hstsMaxAgeSeconds() > 0) {
                                hsts.maxAgeInSeconds(security.hstsMaxAgeSeconds()).includeSubDomains(true);
                            } else {
                                hsts.disable();
                            }
                        }))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Geçerli bir access token gerekli.\"}");
                }).accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"Bu işlem için yetkiniz yok.\"}");
                }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/logout-all").authenticated()
                        .requestMatchers("/api/customer/v1/auth/logout-all").hasRole("CUSTOMER")
                        // Faz 9: doğrulama e-postası istemek oturum gerektirir; sıfırlama/doğrulama
                        // uçlarında kimlik token'ın kendisiyle kanıtlanır, bu yüzden açıktır.
                        .requestMatchers("/api/auth/email/verification", "/api/auth/email/status").authenticated()
                        .requestMatchers("/api/customer/v1/auth/email/verification", "/api/customer/v1/auth/email/status").hasRole("CUSTOMER")
                        .requestMatchers("/api/auth/**", "/api/customer/v1/auth/**", "/api/public/**", "/api/health").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("OPS")
                        .requestMatchers("/api/customer/v1/**").hasRole("CUSTOMER")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(customerJwtAuthFilter, JwtAuthFilter.class)
                .addFilterAfter(new com.saas.backend.operations.RateLimitFilter(limiter,operations,metrics), CustomerJwtAuthFilter.class)
                .addFilterAfter(new com.saas.backend.ops.OpsTokenFilter(opsToken), com.saas.backend.operations.RateLimitFilter.class);
        return http.build();
    }
}
