-- Refresh token rotasyonu: kullanılmış refresh token'ların jti kaydı (yeniden kullanım tespiti).
create table used_refresh_tokens (
    jti        uuid primary key,
    owner_type varchar(16) not null check (owner_type in ('STAFF', 'CUSTOMER')),
    owner_id   uuid not null,
    used_at    timestamptz not null default now(),
    expires_at timestamptz not null
);
create index idx_used_refresh_tokens_expires on used_refresh_tokens (expires_at);
