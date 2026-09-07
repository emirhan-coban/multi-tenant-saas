package com.saas.backend.config;

import com.saas.backend.auth.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
            case "EMAIL_EXISTS", "SLOT_UNAVAILABLE", "STALE_VERSION", "IDEMPOTENCY_CONFLICT", "INVALID_TRANSITION" -> HttpStatus.CONFLICT;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_CREDENTIALS" -> HttpStatus.UNAUTHORIZED;
            case "NO_TENANT" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };

        Map<String, Object> body = new HashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());

        return ResponseEntity.status(status).body(body);
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
