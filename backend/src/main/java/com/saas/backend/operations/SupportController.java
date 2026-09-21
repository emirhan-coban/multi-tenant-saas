package com.saas.backend.operations;

import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.ops.OpsMetrics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/support/requests")
public class SupportController {
    public enum Category { TECHNICAL,BILLING,CANCELLATION,PRIVACY,OTHER }
    public record Create(@NotNull Category category,@NotBlank @Size(max=160) String subject,@NotBlank @Size(min=10,max=4000) String message) {}
    private final JdbcTemplate db; private final OpsMetrics metrics; private final String owner;
    public SupportController(JdbcTemplate db,OpsMetrics metrics,@Value("${app.support.owner:operations}") String owner) {
        this.db=db;this.metrics=metrics;this.owner=owner;
    }
    @PostMapping @Transactional @PreAuthorize("hasAnyRole('OWNER','MANAGER','STAFF')")
    public Map<String,Object> create(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody Create input) {
        context(user); UUID id=UUID.randomUUID();
        db.update("insert into support_requests(id,tenant_id,created_by,category,subject,message,assigned_owner) values (?,?,?,?,?,?,?)",
                id,user.tenantId(),user.userId(),input.category().name(),input.subject().trim(),input.message().trim(),owner);
        metrics.supportRequest(input.category().name().toLowerCase());
        return db.queryForMap("select id,category,subject,status,assigned_owner as \"assignedOwner\",created_at as \"createdAt\" from support_requests where id=?",id);
    }
    @GetMapping @Transactional(readOnly=true) @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String,Object>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        context(user); return db.queryForList("""
            select id,category,subject,message,status,assigned_owner as "assignedOwner",created_at as "createdAt",resolved_at as "resolvedAt"
            from support_requests where tenant_id=? order by created_at desc limit 100
            """,user.tenantId());
    }
    private void context(AuthenticatedUser user) { db.queryForObject("select set_config('app.current_tenant',?,true)",String.class,user.tenantId().toString()); }
}
