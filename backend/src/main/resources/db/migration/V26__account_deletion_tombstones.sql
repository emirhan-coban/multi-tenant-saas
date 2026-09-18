create table account_deletion_tombstones (
    subject_type varchar(16) not null check (subject_type in ('CUSTOMER')),
    subject_id uuid not null,
    deleted_at timestamptz not null default now(),
    primary key (subject_type, subject_id)
);

create index idx_account_deletion_tombstones_time
    on account_deletion_tombstones(deleted_at);