alter table booking_requests add column cancellation_key uuid;
alter table booking_requests add column cancelled_at timestamptz;
alter table booking_requests add column cancellation_source varchar(16)
    check (cancellation_source in ('CUSTOMER', 'STAFF', 'SYSTEM'));

update booking_requests
set cancelled_at = coalesce(updated_at, now()), cancellation_source = 'SYSTEM'
where status = 'CANCELLED';

alter table booking_requests add constraint booking_request_cancellation_fields check (
    (status = 'CANCELLED') = (cancelled_at is not null and cancellation_source is not null)
);
create unique index uq_booking_request_customer_cancellation_key
    on booking_requests(tenant_id, customer_id, cancellation_key)
    where cancellation_key is not null;

create function resolve_customer_booking_tenants(requested_account uuid)
returns table(tenant_id uuid)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select distinct c.tenant_id
    from tenant_customers c
    where c.customer_account_id = requested_account
$$;

alter table audit_log alter column actor_id drop not null;
alter table audit_log add column customer_actor_id uuid references customer_accounts(id);
alter table audit_log add constraint audit_log_single_actor check (
    num_nonnulls(actor_id, customer_actor_id) = 1
);

create index idx_audit_customer_actor on audit_log(customer_actor_id, created_at desc)
    where customer_actor_id is not null;
