-- Faz 8: web push, feature flag, oturum iptali, veri saklama ve operasyon metrikleri.

-- ---------------------------------------------------------------------------
-- Web push abonelikleri
-- Abonelik bir tarayıcı/cihaz ile hesabın eşleşmesidir; müşteri hesapları birden fazla
-- işletmede kullanıldığı için tablo tenant verisi değildir. Sahip kimliği uygulama
-- katmanında (JWT) doğrulanır, uç nokta yalnızca izinli push servislerine gidebilir.
-- ---------------------------------------------------------------------------
create table push_subscriptions (
    id uuid primary key default gen_random_uuid(),
    owner_type varchar(16) not null check (owner_type in ('STAFF', 'CUSTOMER')),
    owner_id uuid not null,
    endpoint varchar(1024) not null,
    endpoint_hash char(64) not null,
    p256dh varchar(128) not null,
    auth_secret varchar(64) not null,
    user_agent varchar(200),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_success_at timestamptz,
    failure_count integer not null default 0 check (failure_count >= 0),
    disabled_at timestamptz,
    disabled_reason varchar(40),
    constraint push_subscription_https check (endpoint like 'https://%'),
    constraint push_subscription_disabled check ((disabled_at is null) = (disabled_reason is null))
);

create unique index uq_push_subscription_endpoint on push_subscriptions(endpoint_hash);
create index idx_push_subscription_owner on push_subscriptions(owner_type, owner_id) where disabled_at is null;

create table push_deliveries (
    id uuid primary key default gen_random_uuid(),
    subscription_id uuid not null references push_subscriptions(id) on delete cascade,
    tenant_id uuid references tenants(id) on delete cascade,
    event_type varchar(64) not null,
    title varchar(80) not null,
    body varchar(200) not null,
    url varchar(200) not null check (url like '/%' and url not like '//%'),
    status varchar(16) not null default 'PENDING'
        check (status in ('PENDING', 'SENT', 'FAILED', 'DROPPED')),
    attempts integer not null default 0 check (attempts between 0 and 10),
    available_at timestamptz not null default now(),
    last_status integer,
    created_at timestamptz not null default now(),
    finished_at timestamptz,
    constraint push_delivery_finished check ((status = 'PENDING') = (finished_at is null))
);

create index idx_push_deliveries_due on push_deliveries(available_at) where status = 'PENDING';
create index idx_push_deliveries_finished on push_deliveries(finished_at) where status <> 'PENDING';

-- ---------------------------------------------------------------------------
-- Feature flag: global satır (tenant_id null) + işletme bazlı geçersiz kılma.
-- ---------------------------------------------------------------------------
create table feature_flags (
    id uuid primary key default gen_random_uuid(),
    flag_key varchar(64) not null
        check (flag_key in ('booking.3d', 'push.notifications', 'pwa.install', 'booking.trusted_auto_confirm')),
    tenant_id uuid references tenants(id) on delete cascade,
    enabled boolean not null,
    reason varchar(250),
    updated_by uuid references users(id),
    updated_at timestamptz not null default now()
);

create unique index uq_feature_flag_global on feature_flags(flag_key) where tenant_id is null;
create unique index uq_feature_flag_tenant on feature_flags(flag_key, tenant_id) where tenant_id is not null;

insert into feature_flags(flag_key, tenant_id, enabled, reason) values
    ('booking.3d', null, true, 'Faz 7 varsayılanı'),
    ('push.notifications', null, true, 'Faz 8 varsayılanı'),
    ('pwa.install', null, true, 'Faz 8 varsayılanı'),
    ('booking.trusted_auto_confirm', null, true, 'Faz V2-5 varsayılanı');

-- ---------------------------------------------------------------------------
-- Oturum iptali: bu andan önce üretilmiş refresh token'lar reddedilir.
-- ---------------------------------------------------------------------------
alter table users add column sessions_valid_after timestamptz;
alter table customer_accounts add column sessions_valid_after timestamptz;

-- ---------------------------------------------------------------------------
-- Veri saklama işlerinin kanıt kaydı (tenant verisi içermez, yalnızca sayılar).
-- ---------------------------------------------------------------------------
create table data_retention_runs (
    id uuid primary key default gen_random_uuid(),
    started_at timestamptz not null,
    finished_at timestamptz not null default now(),
    deleted jsonb not null,
    tenant_count integer not null check (tenant_count >= 0)
);

-- Ürettiği tek şey tenant'lar arası kuyruk derinliği sayılarıdır; satır içeriği dönmez.
-- FORCE RLS olan outbox/booking_requests için metrik toplayıcı her tenant bağlamını kurmak zorunda kalmaz.
create function ops_queue_depth()
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
$$;

revoke all on function ops_queue_depth() from public;
