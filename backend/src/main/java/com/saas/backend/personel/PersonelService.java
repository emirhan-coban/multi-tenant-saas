package com.saas.backend.personel;

import com.saas.backend.config.AuthenticatedUser;
import com.saas.backend.tenant.Tenant;
import com.saas.backend.tenant.TenantRepository;
import com.saas.backend.user.User;
import com.saas.backend.user.UserRepository;
import com.saas.backend.membership.Membership;
import com.saas.backend.membership.MembershipRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Service
public class PersonelService {

    private static final String SIFRE_KARAKTERLERI = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();

    private final PersonelRepository personelRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;

    public PersonelService(
            PersonelRepository personelRepository,
            UserRepository userRepository,
            TenantRepository tenantRepository,
            MembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.personelRepository = personelRepository;
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public PersonelDtos.DavetSonucu davetEt(AuthenticatedUser currentUser, PersonelDtos.DavetEtRequest request) {
        Tenant tenant = tenantRepository.getReferenceById(currentUser.tenantId());

        User user = userRepository.findByEmail(request.email()).orElse(null);
        String geciciSifre = null;

        if (user == null) {
            geciciSifre = rastgeleSifreUret();
            user = new User();
            user.setEmail(request.email());
            user.setFullName(request.displayName());
            user.setPasswordHash(passwordEncoder.encode(geciciSifre));
            userRepository.save(user);
        }

        Membership membership = new Membership();
        membership.setUser(user);
        membership.setTenant(tenant);
        membership.setRole("STAFF");
        membershipRepository.save(membership);

        Personel personel = new Personel();
        personel.setTenant(tenant);
        personel.setUser(user);
        personel.setDisplayName(request.displayName());
        personelRepository.save(personel);

        return new PersonelDtos.DavetSonucu(personel.getId(), user.getEmail(), geciciSifre);
    }

    @Transactional(readOnly = true)
    public List<PersonelDtos.PersonelResponse> listele() {
        return personelRepository.findByActiveTrue()
                .stream()
                .map(p -> new PersonelDtos.PersonelResponse(p.getId(), p.getDisplayName(), p.isActive()))
                .toList();
    }

    private String rastgeleSifreUret() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(SIFRE_KARAKTERLERI.charAt(random.nextInt(SIFRE_KARAKTERLERI.length())));
        }
        return sb.toString();
    }
}
