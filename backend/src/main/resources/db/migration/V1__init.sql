create extension if not exists "pgcrypto";

create table tenants (
                         id          uuid primary key default gen_random_uuid(),
                         name        varchar(200) not null,
                         slug        varchar(100) not null unique,
                         timezone    varchar(64)  not null default 'Istanbul',
                         plan        varchar(20)  not null default 'FREE',
                         created_at  timestamptz  not null default now()
);

create table users (
                       id             uuid primary key default gen_random_uuid(),
                       email          varchar(255) not null unique,
                       password_hash  varchar(255) not null,
                       full_name      varchar(200) not null,
                       created_at     timestamptz  not null default now()
);

create table memberships (
                             id         uuid primary key default gen_random_uuid(),
                             user_id    uuid not null references users(id) on delete cascade,
                             tenant_id  uuid not null references tenants(id) on delete cascade,
                             role       varchar(20) not null,
                             created_at timestamptz not null default now(),
                             unique (user_id, tenant_id)
);

create index idx_memberships_tenant on memberships(tenant_id);