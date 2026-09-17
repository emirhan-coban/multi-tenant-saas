alter table booking_settings
    add column booking_horizon_days integer not null default 30
        check (booking_horizon_days between 1 and 365),
    add column minimum_lead_minutes integer not null default 60
        check (minimum_lead_minutes between 0 and 10080),
    add column request_expiry_minutes integer not null default 15
        check (request_expiry_minutes between 5 and 1440),
    add column cancellation_cutoff_minutes integer not null default 120
        check (cancellation_cutoff_minutes between 0 and 10080);

create table booking_reschedule_requests (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    booking_request_id uuid not null references booking_requests(id) on delete cascade,
    appointment_id uuid not null references appointments(id) on delete cascade,
    customer_id uuid not null references tenant_customers(id) on delete cascade,
    requested_starts_at timestamptz not null,
    requested_busy_until timestamptz not null,
    appointment_version integer not null check (appointment_version >= 0),
    status varchar(24) not null default 'PENDING_APPROVAL'
        check (status in ('PENDING_APPROVAL','APPROVED','REJECTED','NEEDS_RESELECTION','CANCELLED')),
    idempotency_key uuid not null,
    request_hash varchar(64) not null,
    decided_by uuid references users(id),
    decided_at timestamptz,
    decision_key uuid,
    rejection_reason varchar(500),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version integer not null default 0,
    constraint booking_reschedule_times check (requested_starts_at < requested_busy_until),
    constraint booking_reschedule_decision check (
        (status = 'PENDING_APPROVAL' and decided_at is null and decided_by is null)
        or (status <> 'PENDING_APPROVAL' and decided_at is not null)
    ),
    unique (tenant_id, customer_id, idempotency_key)
);

create unique index uq_booking_reschedule_active
    on booking_reschedule_requests(tenant_id, booking_request_id)
    where status = 'PENDING_APPROVAL';
create index idx_booking_reschedule_queue
    on booking_reschedule_requests(tenant_id, status, created_at);

alter table booking_reschedule_requests enable row level security;
alter table booking_reschedule_requests force row level security;
create policy booking_reschedule_tenant on booking_reschedule_requests
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

create table calendar_closures (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    staff_id uuid references staff(id) on delete cascade,
    closure_type varchar(16) not null check (closure_type in ('BREAK','CLOSED_DAY')),
    starts_at timestamptz not null,
    ends_at timestamptz not null,
    reason varchar(255),
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    version integer not null default 0,
    constraint calendar_closure_times check (starts_at < ends_at)
);

create index idx_calendar_closures_time
    on calendar_closures(tenant_id, starts_at, ends_at);
alter table calendar_closures enable row level security;
alter table calendar_closures force row level security;
create policy calendar_closures_tenant on calendar_closures
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

create table in_app_notifications (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    recipient_type varchar(16) not null check (recipient_type in ('STAFF','CUSTOMER')),
    recipient_id uuid not null,
    event_type varchar(64) not null,
    title varchar(160) not null,
    body varchar(500) not null,
    related_type varchar(40),
    related_id uuid,
    read_at timestamptz,
    created_at timestamptz not null default now()
);

create index idx_in_app_notifications_recipient
    on in_app_notifications(recipient_type, recipient_id, created_at desc);
alter table in_app_notifications enable row level security;
alter table in_app_notifications force row level security;
create policy in_app_notifications_tenant on in_app_notifications
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

create function resolve_customer_notification(requested_notification uuid, requested_account uuid)
returns table(tenant_id uuid)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select n.tenant_id from in_app_notifications n
    where n.id=requested_notification and n.recipient_type='CUSTOMER' and n.recipient_id=requested_account
$$;
