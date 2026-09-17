create table booking_settings (
    tenant_id uuid primary key references tenants(id) on delete cascade,
    trusted_auto_confirm_enabled boolean not null default true,
    max_future_appointments integer not null default 2 check (max_future_appointments between 0 and 20),
    max_daily_bookings integer not null default 3 check (max_daily_bookings between 1 and 50),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version integer not null default 0
);

insert into booking_settings(tenant_id)
select id from tenants;

create function create_default_booking_settings()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    insert into booking_settings(tenant_id) values (new.id);
    return new;
end;
$$;

create trigger tenant_default_booking_settings
after insert on tenants
for each row execute function create_default_booking_settings();

alter table booking_settings enable row level security;
alter table booking_settings force row level security;
create policy booking_settings_tenant on booking_settings
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- Pending requests are a queue item, not a calendar reservation. Definitive
-- appointments and advisory locks remain the authority for slot ownership.
alter table booking_requests drop constraint no_pending_booking_hold_overlap;
