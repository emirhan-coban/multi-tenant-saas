package com.saas.backend.account;

import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Faz 9 — hesap güvenliği uçları.
 *
 * <p>"Şifremi unuttum" her zaman 202 döner: e-postanın kayıtlı olup olmadığı yanıttan
 * anlaşılamaz. Sıfırlama ve doğrulama uçları herkese açıktır çünkü kimlik token ile
 * kanıtlanır; doğrulama e-postası isteme ucu ise oturum gerektirir.</p>
 */
@RestController
public class AccountSecurityController {
    private final AccountSecurityService service;
    private final KvkkService kvkk;

    public AccountSecurityController(AccountSecurityService service, KvkkService kvkk) {
        this.service = service;
        this.kvkk = kvkk;
    }

    /** Aydınlatma metninin yürürlükteki sürümü; kayıt ekranları giriş yapmadan okur. */
    @GetMapping("/api/public/kvkk/policy")
    public java.util.Map<String, String> policy() {
        return java.util.Map.of("version", kvkk.policyVersion());
    }

    // --------------------------------------------------------------- işletme kullanıcıları

    @PostMapping("/api/auth/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody AccountSecurityDtos.ForgotPassword request) {
        service.requestPasswordReset(request.email(), false);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/auth/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody AccountSecurityDtos.ResetPassword request) {
        service.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/auth/email/verification")
    public ResponseEntity<Void> requestEmailVerification(@AuthenticationPrincipal AuthenticatedUser user) {
        service.requestEmailVerification(user.userId(), false);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/auth/email/verify")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody AccountSecurityDtos.VerifyEmail request) {
        service.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/auth/email/status")
    public AccountSecurityDtos.EmailStatus emailStatus(@AuthenticationPrincipal AuthenticatedUser user) {
        return new AccountSecurityDtos.EmailStatus(service.emailVerified(user.userId(), false));
    }

    // --------------------------------------------------------------- müşteriler

    @PostMapping("/api/customer/v1/auth/password/forgot")
    public ResponseEntity<Void> customerForgotPassword(@Valid @RequestBody AccountSecurityDtos.ForgotPassword request) {
        service.requestPasswordReset(request.email(), true);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/customer/v1/auth/password/reset")
    public ResponseEntity<Void> customerResetPassword(@Valid @RequestBody AccountSecurityDtos.ResetPassword request) {
        service.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/customer/v1/auth/email/verification")
    public ResponseEntity<Void> customerRequestEmailVerification(@AuthenticationPrincipal CustomerPrincipal principal) {
        service.requestEmailVerification(principal.customerId(), true);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/customer/v1/auth/email/verify")
    public ResponseEntity<Void> customerVerifyEmail(@Valid @RequestBody AccountSecurityDtos.VerifyEmail request) {
        service.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/customer/v1/auth/email/status")
    public AccountSecurityDtos.EmailStatus customerEmailStatus(@AuthenticationPrincipal CustomerPrincipal principal) {
        return new AccountSecurityDtos.EmailStatus(service.emailVerified(principal.customerId(), true));
    }
}
