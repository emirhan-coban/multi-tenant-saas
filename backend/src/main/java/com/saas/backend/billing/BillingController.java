package com.saas.backend.billing;

import com.saas.backend.config.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
public class BillingController {
    private final BillingService billing;
    private final String publicBaseUrl;
    public BillingController(BillingService billing,@Value("${app.public-base-url:http://localhost:5173}") String publicBaseUrl) {
        this.billing=billing; this.publicBaseUrl=publicBaseUrl;
    }
    @GetMapping("/api/billing/subscription") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public BillingDtos.SubscriptionStatus status(@AuthenticationPrincipal AuthenticatedUser user) { return billing.status(user); }
    @PostMapping("/api/billing/checkout") @PreAuthorize("hasRole('OWNER')")
    public BillingDtos.CheckoutResponse checkout(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody BillingDtos.CheckoutRequest request) {
        return billing.checkout(user,request);
    }
    @DeleteMapping("/api/billing/subscription") @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthenticatedUser user) { billing.cancelAtPeriodEnd(user); return ResponseEntity.noContent().build(); }
    @GetMapping("/api/billing/invoices") @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String,Object>> invoices(@AuthenticationPrincipal AuthenticatedUser user) { return billing.invoices(user); }
    @PatchMapping("/api/billing/invoices/{orderReference}") @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> invoiceReference(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable String orderReference,
                                                  @Valid @RequestBody BillingDtos.InvoiceReference body) {
        billing.attachInvoiceReference(user,orderReference,body.reference()); return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/public/billing/iyzico/callback")
    public ResponseEntity<Void> callback(@RequestParam String token) {
        boolean success=billing.completeCheckout(token);
        String separator=publicBaseUrl.endsWith("/")?"":"/";
        return ResponseEntity.status(303).header(HttpHeaders.LOCATION, URI.create(publicBaseUrl+separator+"app/plan?checkout="+(success?"success":"failed")).toString()).build();
    }
    @PostMapping("/api/public/billing/iyzico/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(value="X-IYZ-SIGNATURE-V3",required=false) String signature,
                                        @Valid @RequestBody BillingDtos.Webhook event) {
        billing.webhook(event,signature); return ResponseEntity.noContent().build();
    }
}
