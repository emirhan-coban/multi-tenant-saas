-- Faz 9: şifre sıfırlama ve e-posta doğrulama.
--
-- Token'ın kendisi asla saklanmaz; yalnızca SHA-256 özeti tutulur. Tablo tenant verisi
-- içermez (hesaplar birden çok işletmede kullanılabilir), bu yüzden RLS uygulanmaz;
-- erişim uygulama katmanında token bilgisiyle kanıtlanır.

create table account_tokens (
    id uuid primary key default gen_random_uuid(),
    subject_type varchar(16) not null check (subject_type in ('STAFF', 'CUSTOMER')),
    subject_id uuid not null,
    purpose varchar(24) not null check (purpose in ('PASSWORD_RESET', 'EMAIL_VERIFY')),
    token_hash char(64) not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    used_at timestamptz,
    constraint account_token_window check (expires_at > created_at),
    constraint account_token_used check (used_at is null or used_at >= created_at)
);

create unique index uq_account_token_hash on account_tokens(token_hash);
-- Aynı amaç için yalnızca bir açık token bulunur; yeni istek öncekini geçersiz kılar.
create unique index uq_account_token_open on account_tokens(subject_type, subject_id, purpose)
    where used_at is null;
create index idx_account_token_throttle on account_tokens(subject_type, subject_id, purpose, created_at);
create index idx_account_token_expired on account_tokens(expires_at) where used_at is null;

-- E-posta doğrulaması: mevcut hesaplar geriye dönük doğrulanmış sayılmaz,
-- doğrulama zorunlu bir kapı değil; yalnızca görünür bir durumdur.
alter table users add column email_verified_at timestamptz;
alter table customer_accounts add column email_verified_at timestamptz;
