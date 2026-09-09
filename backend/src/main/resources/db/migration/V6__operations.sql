alter table appointments add column price_amount numeric(10,2);
-- Older appointments have no reliable historical price; do not invent revenue.
create index idx_appointments_created on appointments(tenant_id, created_at);
create table audit_log (
 id uuid primary key default gen_random_uuid(), tenant_id uuid not null,
 actor_id uuid not null, action varchar(80) not null, entity_type varchar(50) not null,
 entity_id uuid not null, details jsonb not null default '{}', created_at timestamptz not null default now()
);
create index idx_audit_tenant_time on audit_log(tenant_id, created_at desc);
alter table audit_log enable row level security;
alter table audit_log force row level security;
create policy audit_tenant on audit_log using (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid);
create function reject_audit_mutation() returns trigger language plpgsql as $$
begin raise exception 'Audit records are immutable'; end $$;
create trigger immutable_audit before update or delete on audit_log for each row execute function reject_audit_mutation();
create trigger immutable_audit_truncate before truncate on audit_log for each statement execute function reject_audit_mutation();
create table notification_preferences (
 tenant_id uuid not null references tenants(id), user_id uuid not null references users(id),
 reminders_enabled boolean not null default true, primary key(tenant_id,user_id)
);
alter table notification_preferences enable row level security;
alter table notification_preferences force row level security;
create policy preferences_tenant on notification_preferences using (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid);
create table outbox (
 id uuid primary key default gen_random_uuid(), tenant_id uuid not null references tenants(id),
 recipient_id uuid not null references users(id), appointment_id uuid not null,
 event_type varchar(50) not null, payload jsonb not null, status varchar(20) not null default 'PENDING',
 attempts integer not null default 0, available_at timestamptz not null default now(),
 created_at timestamptz not null default now(), sent_at timestamptz,
 constraint outbox_status check(status in ('PENDING','SENT','SKIPPED','FAILED'))
);
create index idx_outbox_due on outbox(available_at) where status='PENDING';
alter table outbox enable row level security;
alter table outbox force row level security;
create policy outbox_tenant on outbox using (tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid);
create table shedlock (name varchar(64) primary key, lock_until timestamp not null, locked_at timestamp not null, locked_by varchar(255) not null);
