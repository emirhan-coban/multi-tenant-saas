-- Hesap güvenliği e-postaları için global, şifreli ve kalıcı teslimat kuyruğu.
-- Alıcı, konu ve gövde uygulama anahtarıyla AES-GCM şifrelenir. token_hash yalnızca
-- mesaj gönderilmeden önce token'ın hâlâ açık/geçerli olduğunu denetlemek için tutulur.

create table account_email_outbox (
    id uuid primary key default gen_random_uuid(),
    token_hash char(64) not null references account_tokens(token_hash) on delete cascade,
    recipient_ciphertext text not null,
    subject_ciphertext text not null,
    body_ciphertext text not null,
    status varchar(16) not null default 'PENDING'
        check (status in ('PENDING', 'PROCESSING', 'SENT', 'SKIPPED', 'FAILED')),
    attempts integer not null default 0 check (attempts between 0 and 5),
    available_at timestamptz not null default now(),
    claimed_at timestamptz,
    created_at timestamptz not null default now(),
    finished_at timestamptz,
    last_error varchar(80),
    constraint account_email_claim check ((status = 'PROCESSING') = (claimed_at is not null)),
    constraint account_email_finished check (
        (status in ('SENT', 'SKIPPED', 'FAILED')) = (finished_at is not null))
);

create unique index uq_account_email_token on account_email_outbox(token_hash);
create index idx_account_email_due on account_email_outbox(available_at, id) where status = 'PENDING';
create index idx_account_email_claimed on account_email_outbox(claimed_at) where status = 'PROCESSING';

-- Mevcut operasyon metriğine global hesap e-postası kuyruğunu da ekle.
create or replace function ops_queue_depth()
returns table(queue_name varchar, pending bigint, oldest_age_seconds bigint)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select 'outbox'::varchar, count(*), coalesce(extract(epoch from now() - min(available_at))::bigint, 0)
    from outbox where status = 'PENDING' and available_at <= now()
    union all
    select 'push'::varchar, count(*), coalesce(extract(epoch from now() - min(available_at))::bigint, 0)
    from push_deliveries where status = 'PENDING' and available_at <= now()
    union all
    select 'booking_approval'::varchar, count(*), coalesce(extract(epoch from now() - min(created_at))::bigint, 0)
    from booking_requests where status = 'PENDING_APPROVAL' and hold_expires_at > now()
    union all
    select 'account_email'::varchar, count(*), coalesce(extract(epoch from now() - min(available_at))::bigint, 0)
    from account_email_outbox where status = 'PENDING' and available_at <= now()
$$;

revoke all on function ops_queue_depth() from public;
grant execute on function ops_queue_depth() to public;
