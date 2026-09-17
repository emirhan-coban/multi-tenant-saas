package com.saas.backend.config;

import com.saas.backend.auth.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import com.saas.backend.booking.BookingRateLimitException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final com.saas.backend.ops.OpsMetrics metrics;

    public GlobalExceptionHandler(com.saas.backend.ops.OpsMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> malformedBody(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("code","VALIDATION_ERROR","message","İstek gövdesi geçersiz. Alanları kontrol edin."));
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> optimistic(Exception ex) {
        return ResponseEntity.status(409).body(Map.of("code","STALE_VERSION",
            "message","Randevu başka biri tarafından değiştirildi. Listeyi yenileyin."));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integrity(org.springframework.dao.DataIntegrityViolationException ex) {
        for (Throwable cause=ex; cause!=null; cause=cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "23P01".equals(sql.getSQLState()))
                return ResponseEntity.status(409).body(Map.of("code","SLOT_UNAVAILABLE",
                    "message","Bu saat başka bir randevuyla çakışıyor. Müsait saatleri yenileyin."));
        }
        return ResponseEntity.status(409).body(Map.of("code","DATA_CONFLICT","message","Kayıt mevcut verilerle çakışıyor."));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "EMAIL_EXISTS", "CUSTOMER_EMAIL_EXISTS", "SLOT_UNAVAILABLE", "CUSTOMER_TIME_CONFLICT",
                    "FUTURE_APPOINTMENT_LIMIT", "DAILY_BOOKING_LIMIT", "STALE_VERSION", "IDEMPOTENCY_CONFLICT",
                    "INVALID_TRANSITION", "ACTIVE_REQUEST_LIMIT", "BOOKING_ALREADY_DECIDED", "BOOKING_REQUEST_EXPIRED",
                    "BOOKING_ALREADY_CANCELLED", "CANCELLATION_WINDOW_CLOSED", "VISIT_CODE_WINDOW_CLOSED",
                    "VISIT_ALREADY_VERIFIED", "RESCHEDULE_WINDOW_CLOSED", "ACTIVE_RESCHEDULE_LIMIT",
                    "RESCHEDULE_ALREADY_DECIDED", "CLOSURE_CONFLICT", "STALE_SCENE_SELECTION", "FEATURE_DISABLED" -> HttpStatus.CONFLICT;
            case "NOT_FOUND", "CUSTOMER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "PUSH_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "INVALID_CREDENTIALS", "INVALID_REFRESH_TOKEN", "INVALID_CUSTOMER_CREDENTIALS", "INVALID_CUSTOMER_REFRESH_TOKEN" -> HttpStatus.UNAUTHORIZED;
            case "NO_TENANT", "QUOTA_EXCEEDED", "CUSTOMER_BLOCKED" -> HttpStatus.FORBIDDEN;
            // Faz 9: hesap güvenliği uçlarında saatlik istek sınırı.
            case "TOO_MANY_REQUESTS" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };

        metrics.apiError(ex.getCode(), status.value());
        Map<String, Object> body = new HashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        body.put("details", ex.getDetails());

        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(BookingRateLimitException.class)
    public ResponseEntity<Map<String, Object>> bookingRateLimit(BookingRateLimitException ex) {
        metrics.abuse("booking_ip_rate_limit");
        return ResponseEntity.status(429)
                .header("Retry-After", Long.toString(ex.getRetryAfterSeconds()))
                .body(Map.of("code", "BOOKING_RATE_LIMIT", "message", ex.getMessage(), "details", Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", "VALIDATION_ERROR");
        body.put("message", "Girdi doğrulama hatası");

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.put(err.getField(), err.getDefaultMessage())
        );
        body.put("details", fieldErrors);

        return ResponseEntity.badRequest().body(body);
    }
}
