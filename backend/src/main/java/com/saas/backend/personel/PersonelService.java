package com.saas.backend.personel;

import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.tenant.Tenant;
import com.saas.backend.tenant.TenantRepository;
import com.saas.backend.user.User;
import com.saas.backend.user.UserRepository;
import com.saas.backend.membership.Membership;
import com.saas.backend.membership.MembershipRepository;
import com.saas.backend.auth.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
public class PersonelService {

    private static final String SIFRE_KARAKTERLERI = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();

    private final PersonelRepository personelRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.saas.backend.operations.OperationsService operations;
    private final com.saas.backend.account.AccountTokenService tokens;
    private final com.saas.backend.account.AccountMailer mailer;
    private final boolean liveEmail;
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public PersonelService(
            PersonelRepository personelRepository,
            UserRepository userRepository,
            TenantRepository tenantRepository,
            MembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            com.saas.backend.operations.OperationsService operations,
            com.saas.backend.account.AccountTokenService tokens,
            com.saas.backend.account.AccountMailer mailer,
            org.springframework.jdbc.core.JdbcTemplate db,
            @org.springframework.beans.factory.annotation.Value("${app.email.enabled:false}") boolean emailEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.email.mode:test}") String emailMode
    ) {
        this.liveEmail = emailEnabled && "live".equals(emailMode);
        this.personelRepository = personelRepository;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.operations = operations;
        this.tokens = tokens;
        this.mailer = mailer;
        this.db = db;
    }

    @Transactional
    public PersonelDtos.DavetSonucu davetEt(AuthenticatedUser currentUser, PersonelDtos.DavetEtRequest request) {
        operations.checkQuota(currentUser,"staff");
        Tenant tenant = tenantRepository.getReferenceById(currentUser.tenantId());

        User user = userRepository.findByEmail(request.email()).orElse(null);
        boolean newAccount = user == null;

        if (user == null) {
            user = new User();
            user.setEmail(request.email());
            user.setFullName(request.displayName());
            user.setPasswordHash(passwordEncoder.encode(rastgeleSifreUret()));
            userRepository.saveAndFlush(user);
        }

        if (membershipRepository.findByUserIdAndTenantId(user.getId(), currentUser.tenantId()).isPresent())
            throw new ApiException("STAFF_ALREADY_MEMBER", "Bu e-posta zaten işletmeye bağlı");

        Membership membership = new Membership();
        membership.setUser(user);
        membership.setTenant(tenant);
        membership.setRole("STAFF");
        membership.setActive(false);
        membershipRepository.save(membership);

        Personel personel = new Personel();
        personel.setTenant(tenant);
        personel.setUser(user);
        personel.setDisplayName(request.displayName());
        personel.setActive(false);
        personelRepository.saveAndFlush(personel);
        UUID invitationId = UUID.randomUUID();
        String token = tokens.issue(com.saas.backend.account.AccountTokenService.Subject.staffInvitation(invitationId),
                com.saas.backend.account.AccountTokenService.Purpose.STAFF_INVITE);
        db.update("""
                insert into staff_invitations(id,tenant_id,user_id,staff_id,token_hash,new_account,created_by)
                values (?,?,?,?,?,?,?)
                """, invitationId, currentUser.tenantId(), user.getId(), personel.getId(),
                com.saas.backend.account.AccountTokenService.hash(token), newAccount, currentUser.userId());
        if (!mailer.staffInvitation(user.getEmail(), token, tenant.getName())) {
            tokens.discard(token);
            throw new ApiException("MAIL_UNAVAILABLE", "Davet e-postası kuyruğa alınamadı. Tekrar deneyin.");
        }
        operations.audit(currentUser,"STAFF_INVITED","staff",personel.getId(),java.util.Map.of());

        // E-posta gerçekten gönderilmiyorsa (demo/test) davet hiç kabul edilemezdi: sahibe bağlantı verilir.
        String link = liveEmail ? null : mailer.staffInvitationLink(token);
        return new PersonelDtos.DavetSonucu(personel.getId(), user.getEmail(), true, link);
    }

    @Transactional
    public void davetiKabulEt(PersonelDtos.DavetKabulRequest request) {
        String tokenHash = com.saas.backend.account.AccountTokenService.hash(request.token());
        var resolved = db.queryForList("select * from staff_invitation_for_token(?)", tokenHash);
        if (resolved.isEmpty()) throw new ApiException("INVALID_ACCOUNT_TOKEN", "Davet bağlantısı geçersiz veya süresi dolmuş.");
        UUID tenantId = (UUID) resolved.getFirst().get("tenant_id");
        db.queryForObject("select set_config('app.current_tenant',?,true)", String.class, tenantId.toString());
        var invites = db.queryForList("""
                select id,user_id,new_account from staff_invitations
                where id=? and token_hash=? and accepted_at is null for update
                """, resolved.getFirst().get("id"), tokenHash);
        if (invites.isEmpty()) throw new ApiException("INVALID_ACCOUNT_TOKEN", "Davet bağlantısı geçersiz veya süresi dolmuş.");
        var invite = invites.getFirst();
        boolean newAccount = Boolean.TRUE.equals(invite.get("new_account"));
        if (newAccount && (request.password() == null || !request.password().matches("^(?=.*[A-ZÇĞİÖŞÜ])(?=.*[a-zçğıöşü])(?=.*\\d).{8,72}$")))
            throw new ApiException("WEAK_PASSWORD", "Şifre en az 8 karakter, büyük/küçük harf ve rakam içermeli");
        var subject = tokens.consume(request.token(), com.saas.backend.account.AccountTokenService.Purpose.STAFF_INVITE);
        UUID userId = (UUID) invite.get("user_id");
        if (!subject.id().equals(invite.get("id"))) throw new ApiException("INVALID_ACCOUNT_TOKEN", "Davet bağlantısı geçersiz veya süresi dolmuş.");
        if (newAccount) db.update("update users set password_hash=?,email_verified_at=coalesce(email_verified_at,now()) where id=?",
                passwordEncoder.encode(request.password()), userId);
        db.update("""
                update memberships m set active=true from staff_invitations i
                where i.id=? and m.tenant_id=i.tenant_id and m.user_id=i.user_id
                """, invite.get("id"));
        db.update("update staff s set active=true from staff_invitations i where i.id=? and s.id=i.staff_id", invite.get("id"));
        db.update("update staff_invitations set accepted_at=now() where id=?", invite.get("id"));
    }

    @Transactional(readOnly = true)
    public List<PersonelDtos.PersonelResponse> listele(AuthenticatedUser currentUser) {
        return personelRepository.findByTenantIdAndActiveTrue(com.saas.backend.config.TenantContext.get())
                .stream()
                .map(p -> new PersonelDtos.PersonelResponse(p.getId(), p.getDisplayName(), p.isActive(),
                        p.getUser() != null && p.getUser().getId().equals(currentUser.userId())))
                .toList();
    }

    /** İşletme sahibi/yönetici kendini berber olarak ekler (tek başına çalışan berber). Tekrarlı çağrı güvenlidir. */
    @Transactional
    public PersonelDtos.PersonelResponse kendiniEkle(AuthenticatedUser currentUser) {
        operations.context(currentUser.tenantId());
        var mevcut = personelRepository.findByTenantIdAndActiveTrue(currentUser.tenantId()).stream()
                .filter(p -> p.getUser() != null && p.getUser().getId().equals(currentUser.userId())).findFirst();
        if (mevcut.isPresent()) {
            var p = mevcut.get();
            return new PersonelDtos.PersonelResponse(p.getId(), p.getDisplayName(), true, true);
        }
        operations.checkQuota(currentUser, "staff");
        User user = userRepository.findById(currentUser.userId())
                .orElseThrow(() -> new ApiException("NOT_FOUND", "Kullanıcı bulunamadı"));
        Tenant tenant = tenantRepository.getReferenceById(currentUser.tenantId());
        var pasif = personelRepository.findByTenantIdAndUserId(currentUser.tenantId(), user.getId());
        Personel personel = pasif.orElseGet(Personel::new);
        personel.setTenant(tenant);
        personel.setUser(user);
        if (pasif.isEmpty()) personel.setDisplayName(user.getFullName());
        personel.setActive(true);
        personelRepository.saveAndFlush(personel);
        operations.audit(currentUser, "STAFF_SELF_ADDED", "staff", personel.getId(), java.util.Map.of());
        return new PersonelDtos.PersonelResponse(personel.getId(), personel.getDisplayName(), true, true);
    }

    /** Henüz kabul edilmemiş davetler (son 48 saat geçerli). */
    @Transactional(readOnly = true)
    public List<PersonelDtos.BekleyenDavet> bekleyenDavetler(AuthenticatedUser currentUser) {
        operations.context(currentUser.tenantId());
        return db.queryForList("""
                select i.id, u.email, s.display_name, i.created_at
                from staff_invitations i join users u on u.id = i.user_id join staff s on s.id = i.staff_id
                where i.tenant_id = ? and i.accepted_at is null
                order by i.created_at desc
                """, currentUser.tenantId()).stream().map(row -> {
            var created = ((java.sql.Timestamp) row.get("created_at")).toInstant();
            var expires = created.plus(java.time.Duration.ofHours(48));
            return new PersonelDtos.BekleyenDavet((UUID) row.get("id"), (String) row.get("email"),
                    (String) row.get("display_name"), created, expires, expires.isBefore(java.time.Instant.now()));
        }).toList();
    }

    @Transactional
    public void erisimiKaldir(AuthenticatedUser currentUser, UUID personelId) {
            operations.context(currentUser.tenantId());
            var personel = personelRepository.findByIdAndTenantId(personelId, currentUser.tenantId())
                .orElseThrow(() -> new ApiException("NOT_FOUND", "Personel bulunamadı"));
            if (!personel.isActive() && !membershipRepository.findByUserIdAndTenantId(personel.getUser().getId(), currentUser.tenantId())
                .map(Membership::isActive).orElse(false)) return;
            personel.setActive(false);
            membershipRepository.findByUserIdAndTenantId(personel.getUser().getId(), currentUser.tenantId())
                .ifPresent(membership -> membership.setActive(false));
            operations.audit(currentUser, "STAFF_ACCESS_REVOKED", "staff", personelId, java.util.Map.of());
    }

    private String rastgeleSifreUret() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(SIFRE_KARAKTERLERI.charAt(random.nextInt(SIFRE_KARAKTERLERI.length())));
        }
        return sb.toString();
    }
}
