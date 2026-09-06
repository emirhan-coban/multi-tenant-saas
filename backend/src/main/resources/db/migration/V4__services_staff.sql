create table services (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null references tenants(id) on delete cascade,
    name          varchar(200) not null,
    duration_min  integer not null,
    price         numeric(10,2) not null,
    buffer_min    integer not null default 0,
    deleted_at    timestamptz,
    created_at    timestamptz not null default now()
);

create index idx_services_tenant on services(tenant_id);

alter table services enable row level security;
alter table services force row level security;

create policy tenant_isolation_services on services
  using (tenant_id = current_setting('app.current_tenant', true)::uuid);


create table staff (
    id            uuid primary key default gen_random_uuid(),
    tenant_id     uuid not null references tenants(id) on delete cascade,
    user_id       uuid not null references users(id) on delete cascade,
    display_name  varchar(200) not null,
    active        boolean not null default true,
    created_at    timestamptz not null default now()
);

create index idx_staff_tenant on staff(tenant_id);

alter table staff enable row level security;
alter table staff force row level security;

create policy tenant_isolation_staff on staff
  using (tenant_id = current_setting('app.current_tenant', true)::uuid);


create table staff_services (
    staff_id    uuid not null references staff(id) on delete cascade,
    service_id  uuid not null references services(id) on delete cascade,
    primary key (staff_id, service_id)
);


create table working_hours (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenants(id) on delete cascade,
    staff_id    uuid not null references staff(id) on delete cascade,
    day_of_week integer not null,
    start_time  time not null,
    end_time    time not null
);

create index idx_working_hours_tenant on working_hours(tenant_id);

alter table working_hours enable row level security;
alter table working_hours force row level security;

create policy tenant_isolation_working_hours on working_hours
  using (tenant_id = current_setting('app.current_tenant', true)::uuid);


create table time_off (
    id          uuid primary key default gen_random_uuid(),
    tenant_id   uuid not null references tenants(id) on delete cascade,
    staff_id    uuid not null references staff(id) on delete cascade,
    starts_at   timestamptz not null,
    ends_at     timestamptz not null,
    reason      varchar(255)
);

create index idx_time_off_tenant on time_off(tenant_id);

alter table time_off enable row level security;
alter table time_off force row level security;

create policy tenant_isolation_time_off on time_off
  using (tenant_id = current_setting('app.current_tenant', true)::uuid);