alter table account_tokens drop constraint account_tokens_subject_type_check;
alter table account_tokens add constraint account_tokens_subject_type_check
    check (subject_type in ('STAFF','CUSTOMER','STAFF_INVITATION'));

alter table staff_invitations enable row level security;
alter table staff_invitations force row level security;

create policy staff_invitation_isolation on staff_invitations for all
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- Public acceptance begins without a tenant context. Only a cryptographic token hash can
-- resolve the owning tenant/invitation; all subsequent reads and writes use normal RLS.
create function staff_invitation_for_token(p_hash text)
returns table(id uuid, tenant_id uuid)
language sql stable security definer set search_path=public,pg_temp as $$
    select i.id,i.tenant_id
    from staff_invitations i
    join account_tokens t on t.token_hash=i.token_hash
    where i.token_hash=p_hash and i.accepted_at is null and t.used_at is null and t.expires_at>now()
$$;
grant execute on function staff_invitation_for_token(text) to public;
