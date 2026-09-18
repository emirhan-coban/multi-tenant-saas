alter table staff add column chair_key varchar(80);

update staff set chair_key = 'chair-' || id::text where chair_key is null;

alter table staff alter column chair_key set not null;
alter table staff alter column chair_key set default ('chair-' || gen_random_uuid()::text);
alter table staff add constraint staff_tenant_chair_key_unique unique (tenant_id, chair_key);

alter table booking_requests add column busy_until timestamptz;

update booking_requests b
set busy_until = b.starts_at + make_interval(mins => s.duration_min + s.buffer_min)
from services s
where s.id = b.service_id and s.tenant_id = b.tenant_id and b.busy_until is null;

alter table booking_requests alter column busy_until set not null;
alter table booking_requests add constraint booking_request_time_order
    check (starts_at < busy_until);
alter table booking_requests add constraint no_pending_booking_hold_overlap exclude using gist
    (staff_id with =, tstzrange(starts_at, busy_until, '[)') with &&)
    where (status = 'PENDING_APPROVAL');

create index idx_booking_requests_staff_time
    on booking_requests(staff_id, starts_at, busy_until)
    where status = 'PENDING_APPROVAL';

create function resolve_public_tenant(requested_slug varchar)
returns table(id uuid, name varchar, slug varchar, timezone varchar)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select t.id, t.name, t.slug, t.timezone
    from tenants t
    where t.slug = lower(trim(requested_slug))
$$;

create function resolve_customer_booking(requested_id uuid, requested_account uuid)
returns table(tenant_id uuid, tenant_slug varchar)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select b.tenant_id, t.slug
    from booking_requests b
    join tenant_customers c on c.id = b.customer_id and c.tenant_id = b.tenant_id
    join tenants t on t.id = b.tenant_id
    where b.id = requested_id and c.customer_account_id = requested_account
$$;
