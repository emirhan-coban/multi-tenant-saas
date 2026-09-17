create table customer_accounts (
    id uuid primary key default gen_random_uuid(),
    email varchar(255) not null,
    password_hash varchar(255) not null,
    full_name varchar(200) not null,
    phone varchar(30),
    active boolean not null default true,
    created_at timestamptz not null default now(),
    constraint customer_accounts_email_lowercase check (email = lower(email))
);

create unique index uq_customer_accounts_email on customer_accounts(email);

create table tenant_customers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    customer_account_id uuid not null references customer_accounts(id) on delete cascade,
    trust_state varchar(24) not null default 'NEW'
        check (trust_state in ('NEW','TRUSTED','REVIEW_REQUIRED','BLOCKED')),
    completed_count integer not null default 0 check (completed_count >= 0),
    no_show_count integer not null default 0 check (no_show_count >= 0),
    blocked_reason varchar(250),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version integer not null default 0,
    unique (tenant_id, customer_account_id),
    unique (tenant_id, id),
    check ((trust_state = 'BLOCKED') = (blocked_reason is not null))
);

create index idx_tenant_customers_account on tenant_customers(customer_account_id);
alter table tenant_customers enable row level security;
alter table tenant_customers force row level security;
create policy tenant_isolation_tenant_customers on tenant_customers
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

alter table appointments add constraint appointments_tenant_id_id_unique unique (tenant_id, id);
alter table appointments add constraint appointments_tenant_customer_fk
    foreign key (tenant_id, customer_id) references tenant_customers(tenant_id, id);

create table booking_requests (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    customer_id uuid not null,
    staff_id uuid not null,
    service_id uuid not null,
    starts_at timestamptz not null,
    note varchar(500),
    status varchar(24) not null
        check (status in ('PENDING_APPROVAL','AUTO_CONFIRMED','APPROVED','REJECTED','EXPIRED','CANCELLED')),
    decision varchar(24) not null
        check (decision in ('REQUIRE_APPROVAL','AUTO_CONFIRM','REJECT')),
    hold_expires_at timestamptz,
    appointment_id uuid,
    idempotency_key varchar(128) not null,
    request_hash varchar(64) not null,
    decided_by uuid references users(id),
    decided_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version integer not null default 0,
    foreign key (tenant_id, customer_id) references tenant_customers(tenant_id, id) on delete cascade,
    foreign key (tenant_id, staff_id) references staff(tenant_id, id),
    foreign key (tenant_id, service_id) references services(tenant_id, id),
    foreign key (tenant_id, appointment_id) references appointments(tenant_id, id),
    unique (tenant_id, customer_id, idempotency_key),
    check ((status = 'PENDING_APPROVAL') = (hold_expires_at is not null)),
    check (status not in ('AUTO_CONFIRMED','APPROVED') or appointment_id is not null),
    check ((decided_at is null) = (decided_by is null))
);

create index idx_booking_requests_tenant_status_created on booking_requests(tenant_id, status, created_at desc);
create index idx_booking_requests_customer_created on booking_requests(customer_id, created_at desc);
create index idx_booking_requests_pending_expiry on booking_requests(hold_expires_at) where status = 'PENDING_APPROVAL';

alter table booking_requests enable row level security;
alter table booking_requests force row level security;
create policy tenant_isolation_booking_requests on booking_requests
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
