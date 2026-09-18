-- Customer deletion must not leave personal data in asynchronous delivery tables.
create or replace function anonymize_customer_appointments(p_account uuid)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    affected integer;
    customer_ids uuid[];
    appointment_ids uuid[];
begin
    select coalesce(array_agg(id), '{}'::uuid[]) into customer_ids
    from tenant_customers where customer_account_id = p_account;

    select coalesce(array_agg(id), '{}'::uuid[]) into appointment_ids
    from appointments where customer_id = any(customer_ids);

    update booking_requests set status = 'CANCELLED', decision = 'REJECT',
                               hold_expires_at = null, decided_by = null, decided_at = null,
                               updated_at = now(), version = version + 1
    where customer_id = any(customer_ids) and status = 'PENDING_APPROVAL';

    update appointments set status = 'CANCELLED'
    where id = any(appointment_ids) and starts_at > now() and status = 'CONFIRMED';

    update appointments set customer_name = 'Silinmiş kullanıcı', customer_phone = null,
                            customer_email = null, email_notifications = false, notes = null
    where id = any(appointment_ids);
    get diagnostics affected = row_count;

    update booking_requests set note = null
    where customer_id = any(customer_ids);

    update outbox set recipient_email = null, payload = '{}'::jsonb
    where appointment_id = any(appointment_ids);

    delete from account_email_outbox
    where token_hash in (select token_hash from account_tokens
                         where subject_type = 'CUSTOMER' and subject_id = p_account);
    delete from account_tokens where subject_type = 'CUSTOMER' and subject_id = p_account;

    delete from push_deliveries
    where subscription_id in (select id from push_subscriptions
                              where owner_type = 'CUSTOMER' and owner_id = p_account);
    delete from push_subscriptions where owner_type = 'CUSTOMER' and owner_id = p_account;
    delete from in_app_notifications where recipient_type = 'CUSTOMER' and recipient_id = p_account;

    return affected;
end;
$$;

grant execute on function anonymize_customer_appointments(uuid) to public;