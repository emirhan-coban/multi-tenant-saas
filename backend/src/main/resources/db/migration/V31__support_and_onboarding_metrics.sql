create table onboarding_events (
    tenant_id uuid not null references tenants(id) on delete cascade,
    event_type varchar(32) not null check (event_type in ('COMPLETED')),
    occurred_at timestamptz not null default now(),
    duration_seconds bigint not null check (duration_seconds >= 0),
    primary key(tenant_id,event_type)
);

create table support_requests (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    created_by uuid not null references users(id),
    category varchar(32) not null check (category in ('TECHNICAL','BILLING','CANCELLATION','PRIVACY','OTHER')),
    subject varchar(160) not null,
    message text not null,
    status varchar(24) not null default 'OPEN' check (status in ('OPEN','IN_PROGRESS','RESOLVED')),
    assigned_owner varchar(160) not null,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    check (char_length(message) between 10 and 4000)
);
create index idx_support_requests_tenant_status on support_requests(tenant_id,status,created_at desc);

alter table onboarding_events enable row level security;
alter table onboarding_events force row level security;
alter table support_requests enable row level security;
alter table support_requests force row level security;
create policy onboarding_event_isolation on onboarding_events for all
    using (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);
create policy support_request_isolation on support_requests for all
    using (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid);
