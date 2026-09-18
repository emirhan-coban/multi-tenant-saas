package com.saas.backend.auth;

import com.saas.backend.membership.Membership;
import com.saas.backend.membership.MembershipRepository;
import com.saas.backend.tenant.Tenant;
import com.saas.backend.tenant.TenantRepository;
import com.saas.backend.user.User;
import com.saas.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionRevocationService sessions;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final com.saas.backend.account.KvkkService kvkk;

    public AuthService(
        UserRepository userRepository,
        TenantRepository tenantRepository,
        MembershipRepository membershipRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        SessionRevocationService sessions,
        org.springframework.jdbc.core.JdbcTemplate db,
        com.saas.backend.account.KvkkService kvkk
    ) {
        this.sessions = sessions;
        this.db = db;
        this.kvkk = kvkk;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException("EMAIL_EXISTS", "Bu e-posta zaten kayıtlı");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        userRepository.save(user);

        Tenant tenant = new Tenant();
        tenant.setName(request.tenantName());
        tenant.setSlug(generateUniqueSlug(request.tenantName()));
        tenantRepository.saveAndFlush(tenant);
        // Faz 9: kısıtlı veritabanı rolünde RLS gerçekten uygulanır. Yeni işletme satırı
        // yalnızca kendi bağlamında okunabilir; kaydın devamı için bağlam hemen kurulur.
        db.queryForObject("select set_config('app.current_tenant', ?, true)", String.class, tenant.getId().toString());

        // KVKK: kayıt anında yürürlükteki aydınlatma metninin sürümü işlenir.
        kvkk.recordConsent(user.getId(), false);

        Membership membership = new Membership();
        membership.setUser(user);
        membership.setTenant(tenant);
        membership.setRole("OWNER");
        membershipRepository.save(membership);

        String token = jwtService.generateAccessToken(user.getId(), tenant.getId(), "OWNER");
        String refreshToken = jwtService.generateRefreshToken(user.getId(), tenant.getId(), "OWNER");

        return new AuthResponse(
            token,
            refreshToken,
            List.of(new AuthResponse.MembershipInfo(tenant.getId(), tenant.getName(), "OWNER"))
        );
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ApiException("INVALID_CREDENTIALS", "E-posta veya şifre hatalı"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException("INVALID_CREDENTIALS", "E-posta veya şifre hatalı");
        }

        List<Membership> memberships = membershipRepository.findByUserId(user.getId());

        if (memberships.isEmpty()) {
            throw new ApiException("NO_TENANT", "Bu kullanıcıya ait işletme bulunamadı");
        }

        Membership primary = memberships.get(0);

        String token = jwtService.generateAccessToken(
                user.getId(),
                primary.getTenant().getId(),
                primary.getRole()
        );
        String refreshToken = jwtService.generateRefreshToken(
            user.getId(),
            primary.getTenant().getId(),
            primary.getRole()
        );

        List<AuthResponse.MembershipInfo> membershipInfos = memberships.stream()
                .map(m -> new AuthResponse.MembershipInfo(
                        m.getTenant().getId(),
                        m.getTenant().getName(),
                        m.getRole()
                ))
                .toList();

        return new AuthResponse(token, refreshToken, membershipInfos);
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        final io.jsonwebtoken.Claims claims;
        final java.util.UUID userId;
        final java.util.UUID tenantId;
        try {
            claims = jwtService.parseRefreshToken(refreshToken);
            userId = java.util.UUID.fromString(claims.getSubject());
            tenantId = java.util.UUID.fromString(claims.get("tenantId", String.class));
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException ex) {
            throw new ApiException("INVALID_REFRESH_TOKEN", "Oturum yenilenemedi");
        }
        if (sessions.staffTokenRevoked(userId, claims))
            throw new ApiException("INVALID_REFRESH_TOKEN", "Oturum yenilenemedi");
        var memberships = membershipRepository.findByUserId(userId);
        var membership = memberships.stream()
            .filter(item -> item.getTenant().getId().equals(tenantId))
            .findFirst()
            .orElseThrow(() -> new ApiException("INVALID_REFRESH_TOKEN", "Oturum yenilenemedi"));

        String accessToken = jwtService.generateAccessToken(userId, tenantId, membership.getRole());
        String newRefreshToken = jwtService.generateRefreshToken(userId, tenantId, membership.getRole());
        var membershipInfos = memberships.stream()
            .map(item -> new AuthResponse.MembershipInfo(
                item.getTenant().getId(), item.getTenant().getName(), item.getRole()))
            .toList();
        return new AuthResponse(accessToken, newRefreshToken, membershipInfos);
    }

    private String generateUniqueSlug(String tenantName) {
        String base = slugify(tenantName);
        String slug = base;
        int counter = 1;
        // Faz 9: uygulama rolü RLS nedeniyle diğer işletmelerin satırlarını göremez;
        // benzersizlik kontrolü güvenli fonksiyonla yapılır.
        while (Boolean.TRUE.equals(db.queryForObject("select tenant_slug_taken(?)", Boolean.class, slug))) {
            slug = base + "-" + counter;
            counter++;
        }
        return slug;
    }

    private String slugify(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        String withoutAccents = normalized.replaceAll("\\p{M}", "");
        String lower = withoutAccents.toLowerCase(Locale.ROOT);
        String cleaned = lower.replaceAll("[^a-z0-9]+", "-");
        return cleaned.replaceAll("^-|-$", "");
    }
}
