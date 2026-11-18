package com.saas.backend.booking;

import com.saas.backend.auth.ApiException;
import com.saas.backend.config.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ShopSceneService {
    private final EntityManager em;
    private final com.saas.backend.flags.FeatureFlagService flags;

    public ShopSceneService(EntityManager em, com.saas.backend.flags.FeatureFlagService flags) {
        this.em = em;
        this.flags = flags;
    }

    @Transactional(readOnly = true)
    public PublicManifest publicManifest(String slug) {
        var rows = em.createNativeQuery("select tenant_id,scene_id,scene_version,template_key,accent_color from resolve_public_shop_scene(:slug)")
                .setParameter("slug", slug).getResultList();
        if (rows.isEmpty()) throw missing("Yayınlanmış dükkân sahnesi");
        Object[] row = (Object[]) rows.getFirst();
        UUID tenantId = (UUID) row[0];
        context(tenantId);
        // Kill switch: istemci bu hatada liste görünümüne geçer; mevcut seçimler korunur.
        flags.require(com.saas.backend.flags.FeatureFlag.BOOKING_3D, tenantId, "3D dükkân görünümü şu anda kapalı. Liste görünümü kullanılabilir.");
        return new PublicManifest(number(row[2]), row[3].toString(), row[4].toString(), chairs((UUID) row[1], true));
    }

    @Transactional
    public State get(AuthenticatedUser user) {
        context(user.tenantId());
        ensureDraft(user);
        return state(user.tenantId());
    }

    @Transactional
    public State save(AuthenticatedUser user, @Valid Update update) {
        context(user.tenantId());
        validateChairs(user.tenantId(), update.chairs());
        UUID sceneId = draftId(user.tenantId());
        int changed = em.createNativeQuery("""
                update shop_scenes set template_key=:template,accent_color=:accent,row_version=row_version+1
                where id=:id and tenant_id=:tenant and status='DRAFT' and row_version=:revision
                """).setParameter("template", update.templateKey()).setParameter("accent", update.accentColor())
                .setParameter("id", sceneId).setParameter("tenant", user.tenantId())
                .setParameter("revision", update.revision()).executeUpdate();
        if (changed != 1) throw stale();
        em.createNativeQuery("delete from shop_chairs where scene_id=:scene").setParameter("scene", sceneId).executeUpdate();
        insertChairs(user.tenantId(), sceneId, update.chairs());
        return state(user.tenantId());
    }

    @Transactional
    public State publish(AuthenticatedUser user, Publish command) {
        context(user.tenantId());
        UUID draft = draftId(user.tenantId());
        assertRevision(draft, command.revision());
        publishDraft(user, draft);
        ensureDraft(user);
        return state(user.tenantId());
    }

    @Transactional
    public State rollback(AuthenticatedUser user, Rollback command) {
        context(user.tenantId());
        UUID currentDraft = draftId(user.tenantId());
        assertRevision(currentDraft, command.revision());
        Object[] target = sceneByVersion(user.tenantId(), command.targetVersion());
        em.createNativeQuery("delete from shop_scenes where id=:id").setParameter("id", currentDraft).executeUpdate();
        UUID replacement = cloneScene(user, (UUID) target[0], nextVersion(user.tenantId()), "DRAFT");
        publishDraft(user, replacement);
        ensureDraft(user);
        return state(user.tenantId());
    }

    private void publishDraft(AuthenticatedUser user, UUID draft) {
        Number chairs = (Number) em.createNativeQuery("select count(*) from shop_chairs where scene_id=:scene")
                .setParameter("scene", draft).getSingleResult();
        if (chairs.intValue() == 0) throw new ApiException("EMPTY_SCENE", "Yayınlamak için en az bir koltuk eşleyin.");
        em.createNativeQuery("update shop_scenes set status='ARCHIVED' where tenant_id=:tenant and status='PUBLISHED'")
                .setParameter("tenant", user.tenantId()).executeUpdate();
        em.createNativeQuery("update shop_scenes set status='PUBLISHED',published_at=now(),row_version=row_version+1 where id=:id")
                .setParameter("id", draft).executeUpdate();
    }

    private void ensureDraft(AuthenticatedUser user) {
        Number count = (Number) em.createNativeQuery("select count(*) from shop_scenes where tenant_id=:tenant and status='DRAFT'")
                .setParameter("tenant", user.tenantId()).getSingleResult();
        if (count.intValue() > 0) return;
        UUID published = (UUID) em.createNativeQuery("select id from shop_scenes where tenant_id=:tenant and status='PUBLISHED'")
                .setParameter("tenant", user.tenantId()).getSingleResult();
        cloneScene(user, published, nextVersion(user.tenantId()), "DRAFT");
    }

    private UUID cloneScene(AuthenticatedUser user, UUID source, int version, String status) {
        UUID id = (UUID) em.createNativeQuery("""
                insert into shop_scenes(tenant_id,version,status,template_key,accent_color,created_by)
                select tenant_id,:version,:status,template_key,accent_color,:actor from shop_scenes where id=:source
                returning id
                """).setParameter("version", version).setParameter("status", status)
                .setParameter("actor", user.userId()).setParameter("source", source).getSingleResult();
        em.createNativeQuery("""
                insert into shop_chairs(tenant_id,scene_id,chair_key,label,staff_id,position_x,position_z,rotation_y)
                select tenant_id,:target,chair_key,label,staff_id,position_x,position_z,rotation_y
                from shop_chairs where scene_id=:source
                """).setParameter("target", id).setParameter("source", source).executeUpdate();
        return id;
    }

    private State state(UUID tenantId) {
        Scene draft = scene(tenantId, "DRAFT");
        Scene published = scene(tenantId, "PUBLISHED");
        List<Integer> history = em.createNativeQuery("select version from shop_scenes where tenant_id=:tenant and status in ('PUBLISHED','ARCHIVED') order by version desc")
                .setParameter("tenant", tenantId).getResultList().stream().map(ShopSceneService::number).toList();
        List<StaffOption> staff = em.createNativeQuery("select id,display_name from staff where tenant_id=:tenant and active=true order by display_name,id")
                .setParameter("tenant", tenantId).getResultList().stream().map(value -> {
                    Object[] row = (Object[]) value;
                    return new StaffOption((UUID) row[0], row[1].toString());
                }).toList();
        return new State(draft, published, history, staff);
    }

    private Scene scene(UUID tenantId, String status) {
        Object[] row = (Object[]) em.createNativeQuery("""
                select id,version,status,template_key,accent_color,row_version,published_at
                from shop_scenes where tenant_id=:tenant and status=:status
                """).setParameter("tenant", tenantId).setParameter("status", status).getSingleResult();
        return new Scene(number(row[1]), row[2].toString(), row[3].toString(), row[4].toString(), number(row[5]),
                row[6] == null ? null : row[6].toString(), chairs((UUID) row[0], false));
    }

    private List<Chair> chairs(UUID sceneId, boolean activeOnly) {
        return em.createNativeQuery("""
                select c.chair_key,c.label,c.staff_id,p.display_name,c.position_x,c.position_z,c.rotation_y
                from shop_chairs c join staff p on p.id=c.staff_id and p.tenant_id=c.tenant_id
                where c.scene_id=:scene and (:activeOnly=false or p.active=true) order by c.position_x,c.chair_key
                """).setParameter("scene", sceneId).setParameter("activeOnly", activeOnly).getResultList().stream().map(value -> {
                    Object[] row = (Object[]) value;
                    return new Chair(row[0].toString(), row[1].toString(), (UUID) row[2], row[3].toString(),
                            decimal(row[4]), decimal(row[5]), decimal(row[6]));
                }).toList();
    }

    private void validateChairs(UUID tenantId, List<ChairInput> chairs) {
        if (chairs.size() > 3) throw new ApiException("CHAIR_LIMIT", "İlk 3D şablonda en fazla üç koltuk kullanılabilir.");
        var keys = new java.util.HashSet<String>();
        var staff = new java.util.HashSet<UUID>();
        for (ChairInput chair : chairs) {
            if (!keys.add(chair.chairKey()) || !staff.add(chair.staffId()))
                throw new ApiException("DUPLICATE_CHAIR_MAPPING", "Koltuk anahtarı ve personel eşlemesi benzersiz olmalı.");
            Number exists = (Number) em.createNativeQuery("select count(*) from staff where id=:id and tenant_id=:tenant and active=true")
                    .setParameter("id", chair.staffId()).setParameter("tenant", tenantId).getSingleResult();
            if (exists.intValue() != 1) throw missing("Aktif personel");
        }
    }

    private void insertChairs(UUID tenantId, UUID sceneId, List<ChairInput> chairs) {
        for (ChairInput chair : chairs) em.createNativeQuery("""
                insert into shop_chairs(tenant_id,scene_id,chair_key,label,staff_id,position_x,position_z,rotation_y)
                values(:tenant,:scene,:key,:label,:staff,:x,:z,:rotation)
                """).setParameter("tenant", tenantId).setParameter("scene", sceneId)
                .setParameter("key", chair.chairKey()).setParameter("label", chair.label())
                .setParameter("staff", chair.staffId()).setParameter("x", chair.positionX())
                .setParameter("z", chair.positionZ()).setParameter("rotation", chair.rotationY()).executeUpdate();
    }

    private UUID draftId(UUID tenantId) {
        var rows = em.createNativeQuery("select id from shop_scenes where tenant_id=:tenant and status='DRAFT' for update")
                .setParameter("tenant", tenantId).getResultList();
        if (rows.isEmpty()) throw missing("Sahne taslağı");
        return (UUID) rows.getFirst();
    }

    private Object[] sceneByVersion(UUID tenantId, int version) {
        var rows = em.createNativeQuery("select id from shop_scenes where tenant_id=:tenant and version=:version and status in ('PUBLISHED','ARCHIVED')")
                .setParameter("tenant", tenantId).setParameter("version", version).getResultList();
        if (rows.isEmpty()) throw missing("Sahne sürümü");
        return new Object[]{rows.getFirst()};
    }

    private int nextVersion(UUID tenantId) {
        return number(em.createNativeQuery("select coalesce(max(version),0)+1 from shop_scenes where tenant_id=:tenant")
                .setParameter("tenant", tenantId).getSingleResult());
    }

    private void assertRevision(UUID id, int revision) {
        Number count = (Number) em.createNativeQuery("select count(*) from shop_scenes where id=:id and row_version=:revision")
                .setParameter("id", id).setParameter("revision", revision).getSingleResult();
        if (count.intValue() != 1) throw stale();
    }

    private void context(UUID tenantId) {
        em.createNativeQuery("select set_config('app.current_tenant',:tenant,true)")
                .setParameter("tenant", tenantId.toString()).getSingleResult();
    }

    private static int number(Object value) { return ((Number) value).intValue(); }
    private static double decimal(Object value) { return ((Number) value).doubleValue(); }
    private static ApiException missing(String name) { return new ApiException("NOT_FOUND", name + " bulunamadı."); }
    private static ApiException stale() { return new ApiException("STALE_VERSION", "Sahne taslağı başka biri tarafından değiştirildi. Sayfayı yenileyin."); }

    public record PublicManifest(int version, String templateKey, String accentColor, List<Chair> chairs) {}
    public record State(Scene draft, Scene published, List<Integer> history, List<StaffOption> staff) {}
    public record Scene(int version, String status, String templateKey, String accentColor,
                        int revision, String publishedAt, List<Chair> chairs) {}
    public record Chair(String chairKey, String label, UUID staffId, String staffName,
                        double positionX, double positionZ, double rotationY) {}
    public record StaffOption(UUID id, String displayName) {}
    public record Update(@NotBlank String templateKey,
                         @NotNull @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String accentColor,
                         @NotNull @Min(0) Integer revision,
                         @NotNull @Size(max = 3) List<@Valid ChairInput> chairs) {}
    public record ChairInput(@NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,39}$") String chairKey,
                             @NotBlank @Size(max = 60) String label,
                             @NotNull UUID staffId,
                             @NotNull @DecimalMin("-8") @DecimalMax("8") BigDecimal positionX,
                             @NotNull @DecimalMin("-5") @DecimalMax("4") BigDecimal positionZ,
                             @NotNull @DecimalMin("-6.284") @DecimalMax("6.284") BigDecimal rotationY) {}
    public record Publish(@NotNull @Min(0) Integer revision) {}
    public record Rollback(@NotNull @Min(1) Integer targetVersion, @NotNull @Min(0) Integer revision) {}
}
