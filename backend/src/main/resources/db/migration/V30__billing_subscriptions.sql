create table subscription_plans (
    code varchar(40) primary key,
    name varchar(120) not null,
    amount_minor integer not null check (amount_minor > 0),
    currency char(3) not null check (currency = upper(currency)),
    billing_period varchar(16) not null check (billing_period in ('MONTHLY')),
    trial_days integer not null check (trial_days between 0 and 90),
    grace_days integer not null check (grace_days between 0 and 30),
    staff_limit integer,
    appointment_limit integer,
    requests_per_minute integer not null check (requests_per_minute > 0),
    active boolean not null default true,
    created_at timestamptz not null default now()
);

insert into subscription_plans(code,name,amount_minor,currency,billing_period,trial_days,grace_days,staff_limit,appointment_limit,requests_per_minute)
values ('PRO_MONTHLY','Profesyonel',49900,'TRY','MONTHLY',14,7,null,null,300);

create table tenant_subscriptions (
    tenant_id uuid primary key references tenants(id) on delete cascade,
    plan_code varchar(40) not null references subscription_plans(code),
    provider varchar(20) not null default 'IYZICO' check (provider in ('IYZICO')),
    status varchar(32) not null check (status in ('TRIALING','ACTIVE','PAST_DUE','CANCEL_AT_PERIOD_END','RESTRICTED','CANCELLED')),
    provider_subscription_ref varchar(160) unique,
    provider_customer_ref varchar(160),
    trial_ends_at timestamptz,
    current_period_ends_at timestamptz,
    grace_ends_at timestamptz,
    cancel_requested_at timestamptz,
    last_provider_event_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table billing_checkout_sessions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    plan_code varchar(40) not null references subscription_plans(code),
    conversation_id varchar(100) not null unique,
    provider_token_hash char(64) unique,
    expected_amount_minor integer not null,
    expected_currency char(3) not null,
    status varchar(24) not null default 'CREATED' check (status in ('CREATED','INITIALIZED','COMPLETED','FAILED','EXPIRED')),
    expires_at timestamptz not null,
    completed_at timestamptz,
    created_at timestamptz not null default now()
);

create table billing_events (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    provider_event_id varchar(160) not null unique,
    provider_subscription_ref varchar(160) not null,
    provider_order_ref varchar(160) not null,
    event_type varchar(80) not null,
    event_at timestamptz not null,
    payload_hash char(64) not null,
    outcome varchar(24) not null check (outcome in ('APPLIED','DUPLICATE','STALE','REJECTED')),
    received_at timestamptz not null default now()
);

create table billing_invoices (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    provider_order_ref varchar(160) not null unique,
    provider_invoice_ref varchar(160),
    amount_minor integer not null,
    currency char(3) not null,
    status varchar(24) not null check (status in ('PAID','FAILED','REFUNDED')),
    occurred_at timestamptz not null,
    created_at timestamptz not null default now()
);

create index idx_billing_events_tenant_time on billing_events(tenant_id,event_at desc);
create index idx_billing_sessions_tenant on billing_checkout_sessions(tenant_id,created_at desc);

alter table tenant_subscriptions enable row level security;
alter table tenant_subscriptions force row level security;
alter table billing_checkout_sessions enable row level security;
alter table billing_checkout_sessions force row level security;
alter table billing_events enable row level security;
alter table billing_events force row level security;
alter table billing_invoices enable row level security;
alter table billing_invoices force row level security;

create policy tenant_subscription_isolation on tenant_subscriptions for all
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
create policy billing_checkout_isolation on billing_checkout_sessions for all
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
create policy billing_event_isolation on billing_events for all
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
create policy billing_invoice_isolation on billing_invoices for all
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- Webhook/callback önce imzayı veya yüksek entropili token'ı doğrular; ardından yalnız
-- eşleşen tenant UUID'sini alır ve RLS bağlamını kurar. Fonksiyon başka veri döndürmez.
create function billing_tenant_for_subscription(p_reference text) returns uuid
language sql stable security definer set search_path=public,pg_temp as $$
    select tenant_id from tenant_subscriptions where provider_subscription_ref=p_reference
$$;
create function billing_tenant_for_checkout_token(p_hash text) returns uuid
language sql stable security definer set search_path=public,pg_temp as $$
    select tenant_id from billing_checkout_sessions where provider_token_hash=p_hash and expires_at>now()
$$;
create function billing_restrict_expired() returns integer
language plpgsql security definer set search_path=public,pg_temp as $$
declare changed integer;
begin
    update tenant_subscriptions set status='RESTRICTED',updated_at=now()
    where status='PAST_DUE' and grace_ends_at<=now();
    get diagnostics changed = row_count;
    return changed;
end;
$$;
create function billing_due_cancellations()
returns table(tenant_id uuid, provider_subscription_ref varchar)
language sql stable security definer set search_path=public,pg_temp as $$
    select tenant_id,provider_subscription_ref from tenant_subscriptions
    where status='CANCEL_AT_PERIOD_END' and current_period_ends_at<=now()
    order by current_period_ends_at limit 20
$$;
grant execute on function billing_tenant_for_subscription(text) to public;
grant execute on function billing_tenant_for_checkout_token(text) to public;
grant execute on function billing_restrict_expired() to public;
grant execute on function billing_due_cancellations() to public;
