package com.saas.backend.customer;

import com.saas.backend.auth.ApiException;
import com.saas.backend.auth.JwtService;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class CustomerAuthService {
    private final CustomerAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public CustomerAuthService(CustomerAccountRepository accounts, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public CustomerAuthDtos.Response register(CustomerAuthDtos.Register request) {
        String email = normalizeEmail(request.email());
        if (accounts.existsByEmail(email)) {
            throw new ApiException("CUSTOMER_EMAIL_EXISTS", "Bu e-posta ile bir müşteri hesabı zaten var");
        }
        var account = new CustomerAccount();
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setFullName(request.fullName().trim());
        account.setPhone(normalizePhone(request.phone()));
        try {
            return response(accounts.saveAndFlush(account));
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ApiException("CUSTOMER_EMAIL_EXISTS", "Bu e-posta ile bir müşteri hesabı zaten var");
        }
    }

    @Transactional(readOnly = true)
    public CustomerAuthDtos.Response login(CustomerAuthDtos.Login request) {
        var account = accounts.findByEmail(normalizeEmail(request.email()))
                .filter(CustomerAccount::isActive)
                .orElseThrow(this::invalidCredentials);
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw invalidCredentials();
        }
        return response(account);
    }

    @Transactional(readOnly = true)
    public CustomerAuthDtos.Response refresh(String refreshToken) {
        final UUID customerId;
        try {
            customerId = UUID.fromString(jwtService.parseCustomerRefreshToken(refreshToken).getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new ApiException("INVALID_CUSTOMER_REFRESH_TOKEN", "Müşteri oturumu yenilenemedi");
        }
        var account = accounts.findById(customerId)
                .filter(CustomerAccount::isActive)
                .orElseThrow(() -> new ApiException("INVALID_CUSTOMER_REFRESH_TOKEN", "Müşteri oturumu yenilenemedi"));
        return response(account);
    }

    @Transactional(readOnly = true)
    public CustomerAuthDtos.Customer me(UUID customerId) {
        return accounts.findById(customerId)
                .filter(CustomerAccount::isActive)
                .map(this::customer)
                .orElseThrow(() -> new ApiException("CUSTOMER_NOT_FOUND", "Müşteri hesabı bulunamadı"));
    }

    private CustomerAuthDtos.Response response(CustomerAccount account) {
        return new CustomerAuthDtos.Response(
                jwtService.generateCustomerAccessToken(account.getId()),
                jwtService.generateCustomerRefreshToken(account.getId()),
                customer(account)
        );
    }

    private CustomerAuthDtos.Customer customer(CustomerAccount account) {
        return new CustomerAuthDtos.Customer(account.getId(), account.getEmail(), account.getFullName(), account.getPhone());
    }

    private String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePhone(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private ApiException invalidCredentials() {
        return new ApiException("INVALID_CUSTOMER_CREDENTIALS", "E-posta veya şifre hatalı");
    }
}
