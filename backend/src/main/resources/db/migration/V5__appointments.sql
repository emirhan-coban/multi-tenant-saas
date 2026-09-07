create extension if not exists btree_gist;
-- Composite references prevent cross-tenant links even when connected as a superuser.
alter table staff add constraint staff_tenant_id_unique unique (tenant_id, id);
alter table services add constraint services_tenant_id_unique unique (tenant_id, id);
create table appointments (
 id uuid primary key default gen_random_uuid(),
 tenant_id uuid not null references tenants(id) on delete cascade,
 staff_id uuid not null,
 service_id uuid not null,
 customer_id uuid,
 customer_name varchar(200),
 customer_phone varchar(40),
 starts_at timestamptz not null,
 ends_at timestamptz not null,
 busy_until timestamptz not null,
 duration_min integer not null check (duration_min > 0),
 buffer_min integer not null check (buffer_min >= 0),
 status varchar(20) not null default 'CONFIRMED'
   check (status in ('CONFIRMED','COMPLETED','CANCELLED','NO_SHOW')),
 notes varchar(500),
 created_at timestamptz not null default now(),
 version integer not null default 0,
 foreign key (tenant_id,staff_id) references staff(tenant_id,id) on delete cascade,
 foreign key (tenant_id,service_id) references services(tenant_id,id) on delete cascade,
 check (starts_at < ends_at and ends_at <= busy_until),
 constraint no_overlap exclude using gist
   (staff_id with =, tstzrange(starts_at,busy_until,'[)') with &&)
   where (status <> 'CANCELLED')
);
create index idx_appointments_tenant on appointments(tenant_id);
create index idx_appointments_staff_time on appointments(staff_id, starts_at);
alter table appointments enable row level security;
alter table appointments force row level security;
create policy tenant_isolation_appointments on appointments
 using (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid)
 with check (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid);
create table idempotency_keys (
 tenant_id uuid not null references tenants(id) on delete cascade,
 user_id uuid not null,
 key varchar(128) not null,
 request_hash varchar(64) not null,
 response text not null,
 created_at timestamptz not null default now(),
 primary key (tenant_id,user_id,key)
);
alter table idempotency_keys enable row level security;
alter table idempotency_keys force row level security;
create policy tenant_isolation_idempotency on idempotency_keys
 using (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid)
 with check (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid);

