package com.saas.backend.account;

import com.saas.backend.auth.ApiException;
import com.saas.backend.auth.SessionRevocationService;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Faz 9 — KVKK yükümlülükleri: açık rıza kaydı, verinin dışa aktarılması ve hesabın silinmesi.
 *
 * <p><b>Silme neden anonimleştirme?</b> Randevu geçmişi işletmenin ticari ve muhasebe kaydıdır;
 * tamamen silinmesi işletmenin yasal saklama yükümlülüğüyle çakışır. Bu yüzden kişisel alanlar
 * (ad, telefon, e-posta, not) kalıcı olarak silinir, randevu satırı kimliksiz olarak kalır.
 * Hesap bir daha açılamaz: oturumlar düşer, bildirim abonelikleri kapanır, şifre geçersizleşir.</p>
 */
@Service
public class KvkkService {
    private final JdbcTemplate db;
    private final SessionRevocationService sessions;
    private final String policyVersion;
    private final String termsVersion;

    public KvkkService(JdbcTemplate db, SessionRevocationService sessions,
                       @Value("${app.kvkk.policy-version:2026-09}") String policyVersion,
                       @Value("${app.kvkk.terms-version:2026-09}") String termsVersion) {
        this.db = db;
        this.sessions = sessions;
        this.policyVersion = policyVersion;
        this.termsVersion = termsVersion;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public String termsVersion() {
        return termsVersion;
    }

    /** Kayıt sırasında onaylanan aydınlatma metninin sürümünü yazar. */
    @Transactional
    public void recordConsent(UUID account, boolean customer) {
        String subjectType = customer ? "CUSTOMER" : "STAFF";
        db.update(customer
                ? "update customer_accounts set consent_version=?, consent_at=now() where id=? and consent_at is null"
                : "update users set consent_version=?, consent_at=now() where id=? and consent_at is null",
                policyVersion, account);
        db.update("insert into legal_documents(code,version,title) values ('PRIVACY_NOTICE',?,'Kişisel Verilerin Korunması Aydınlatma Metni') on conflict do nothing", policyVersion);
        db.update("insert into legal_documents(code,version,title) values ('TERMS_OF_SERVICE',?,'Hizmet Kullanım Koşulları') on conflict do nothing", termsVersion);
        db.update("""
                insert into legal_acceptances(subject_type,subject_id,document_code,document_version,acceptance_type)
                values (?,?,'PRIVACY_NOTICE',?,'ACKNOWLEDGEMENT') on conflict do nothing
                """, subjectType, account, policyVersion);
        db.update("""
                insert into legal_acceptances(subject_type,subject_id,document_code,document_version,acceptance_type)
                values (?,?,'TERMS_OF_SERVICE',?,'CONTRACT') on conflict do nothing
                """, subjectType, account, termsVersion);
    }

    /** Müşterinin kendi verisinin tamamı, tek bir JSON belgesi olarak. */
    @Transactional
    public String exportCustomer(UUID account) {
        String document = db.queryForObject("select export_customer_data(?)::text", String.class, account);
        if (document == null || "null".equals(document)) throw new ApiException("CUSTOMER_NOT_FOUND", "Müşteri hesabı bulunamadı");
        db.update("insert into kvkk_requests(subject_type,subject_id,action) values ('CUSTOMER',?,'EXPORT')", account);
        return document;
    }

    /** Hesabı kapatır ve kişisel alanları geri döndürülemez biçimde siler. */
    @Transactional
    public void deleteCustomer(UUID account) {
        var locked = db.queryForList("select active,anonymized_at from customer_accounts where id=? for update", account);
        if (locked.isEmpty() || !Boolean.TRUE.equals(locked.getFirst().get("active"))
                || locked.getFirst().get("anonymized_at") != null)
            throw new ApiException("CUSTOMER_NOT_FOUND", "Müşteri hesabı bulunamadı");
        db.update("insert into account_deletion_tombstones(subject_type,subject_id) values ('CUSTOMER',?) on conflict do nothing", account);
        int updated = db.update("""
                update customer_accounts set
                    email = 'silinmis+' || id || '@hesap.gecersiz',
                    full_name = 'Silinmiş kullanıcı',
                    phone = null,
                    password_hash = md5(gen_random_uuid()::text),
                    email_verified_at = null,
                    active = false,
                    anonymized_at = coalesce(anonymized_at, now())
                where id = ? and anonymized_at is null
                """, account);
        if (updated == 0) throw new ApiException("CUSTOMER_NOT_FOUND", "Müşteri hesabı bulunamadı");
        int appointments = db.queryForObject("select anonymize_customer_appointments(?)", Integer.class, account);
        db.update("update account_tokens set used_at=now() where subject_type='CUSTOMER' and subject_id=? and used_at is null", account);
        sessions.revokeCustomer(account);
        db.update("insert into kvkk_requests(subject_type,subject_id,action,affected) values ('CUSTOMER',?,'DELETE',cast(? as jsonb))",
                account, "{\"randevu\":" + appointments + "}");
        LoggerFactory.getLogger(getClass()).info("KVKK silme tamamlandı randevuSayisi={}", appointments);
    }
}
