package com.saas.backend.account;

import com.saas.backend.customer.CustomerPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Faz 9 — KVKK uçları. Müşteri kendi verisini indirir veya hesabını kapatır.
 * Silme geri alınamaz olduğu için istek gövdesinde açık onay metni beklenir.
 */
@RestController
@RequestMapping("/api/customer/v1/account")
public class KvkkController {
    /** Kullanıcının yanlışlıkla silmesini önlemek için yazması gereken onay. */
    static final String CONFIRMATION = "HESABIMI SIL";

    private final KvkkService kvkk;

    public KvkkController(KvkkService kvkk) {
        this.kvkk = kvkk;
    }

    public record DeleteRequest(@NotBlank String confirmation) {
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> export(@AuthenticationPrincipal CustomerPrincipal principal) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"verilerim.json\"")
                .body(kvkk.exportCustomer(principal.customerId()));
    }

    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CustomerPrincipal principal,
                                       @Valid @RequestBody DeleteRequest request) {
        if (!CONFIRMATION.equals(request.confirmation().trim()))
            throw new com.saas.backend.auth.ApiException("CONFIRMATION_REQUIRED",
                    "Silmek için kutuya “" + CONFIRMATION + "” yazın");
        kvkk.deleteCustomer(principal.customerId());
        return ResponseEntity.noContent().build();
    }
}
