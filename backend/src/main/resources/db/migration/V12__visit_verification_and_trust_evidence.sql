alter table tenant_customers add column trusted_at timestamptz;
alter table tenant_customers add column trusted_by uuid references users(id);
alter table tenant_customers add column trust_reason varchar(250);
alter table tenant_customers add column evidence_appointment_id uuid;
alter table tenant_customers add constraint tenant_customer_evidence_appointment_fk
    foreign key (tenant_id, evidence_appointment_id) references appointments(tenant_id, id);

create table visit_verifications (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    appointment_id uuid not null,
    customer_id uuid not null,
    code_hash char(64) not null,
    expires_at timestamptz not null,
    attempt_count integer not null default 0 check (attempt_count between 0 and 5),
    verified_at timestamptz,
    verified_by uuid references users(id),
    invalidated_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    foreign key (tenant_id, appointment_id) references appointments(tenant_id, id) on delete cascade,
    foreign key (tenant_id, customer_id) references tenant_customers(tenant_id, id) on delete cascade,
    unique (tenant_id, appointment_id),
    check ((verified_at is null) = (verified_by is null)),
    check (not (verified_at is not null and invalidated_at is not null))
);

create index idx_visit_verification_customer on visit_verifications(tenant_id, customer_id, created_at desc);
alter table visit_verifications enable row level security;
alter table visit_verifications force row level security;
create policy visit_verification_tenant on visit_verifications
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

create table customer_trust_events (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null,
    customer_id uuid not null,
    from_state varchar(24) not null,
    to_state varchar(24) not null,
    reason varchar(250) not null,
    actor_id uuid not null references users(id),
    evidence_appointment_id uuid,
    created_at timestamptz not null default now(),
    foreign key (tenant_id, customer_id) references tenant_customers(tenant_id, id) on delete cascade,
    foreign key (tenant_id, evidence_appointment_id) references appointments(tenant_id, id),
    check (from_state in ('NEW','TRUSTED','REVIEW_REQUIRED','BLOCKED')),
    check (to_state in ('NEW','TRUSTED','REVIEW_REQUIRED','BLOCKED')),
    check (from_state <> to_state)
);

create unique index uq_customer_trust_evidence_event
    on customer_trust_events(tenant_id, customer_id, evidence_appointment_id, reason)
    where evidence_appointment_id is not null;
create index idx_customer_trust_events on customer_trust_events(tenant_id, customer_id, created_at desc);
alter table customer_trust_events enable row level security;
alter table customer_trust_events force row level security;
create policy customer_trust_event_tenant on customer_trust_events
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
