alter table account_tokens drop constraint account_tokens_purpose_check;
alter table account_tokens add constraint account_tokens_purpose_check
    check (purpose in ('PASSWORD_RESET', 'EMAIL_VERIFY', 'STAFF_INVITE'));

create table staff_invitations (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    staff_id uuid not null references staff(id) on delete cascade,
    token_hash char(64) not null unique references account_tokens(token_hash) on delete cascade,
    new_account boolean not null,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    accepted_at timestamptz
);

create unique index uq_staff_invitation_pending on staff_invitations(tenant_id, user_id)
    where accepted_at is null;
