#!/bin/bash
# Faz 9: uygulama için süper kullanıcı OLMAYAN veritabanı rolü (RLS'i atlayamaz).
# Hem ilk açılışta (docker-entrypoint-initdb.d) hem sonradan tekrar çalıştırılabilir.
set -euo pipefail
DB="${POSTGRES_DB:-saasdb}"
OWNER="${POSTGRES_USER:-admin}"
APP_USER="${APP_DB_USER:-saas_app}"
APP_PASSWORD="${APP_DB_PASSWORD:-saas_app_dev}"

psql -v ON_ERROR_STOP=1 --username "$OWNER" --dbname "$DB" \
     -c "set my.app_user='$APP_USER'; set my.app_password='$APP_PASSWORD'; set my.owner='$OWNER';
do \$\$
declare app text := current_setting('my.app_user'); pwd text := current_setting('my.app_password'); owner text := current_setting('my.owner');
begin
    if not exists (select 1 from pg_roles where rolname = app) then
        execute format('create role %I login', app);
    end if;
    -- Süper kullanıcı değil, RLS'i atlayamaz, şema değiştiremez.
    execute format('alter role %I with login nosuperuser nocreatedb nocreaterole nobypassrls password %L', app, pwd);
    execute format('grant connect on database %I to %I', current_database(), app);
    execute format('grant usage on schema public to %I', app);
    execute format('grant select, insert, update, delete on all tables in schema public to %I', app);
    execute format('grant usage, select on all sequences in schema public to %I', app);
    execute format('grant execute on all functions in schema public to %I', app);
    -- Flyway (şema sahibi) ileride tablo eklerse yetkiler otomatik verilsin.
    execute format('alter default privileges for role %I in schema public grant select, insert, update, delete on tables to %I', owner, app);
    execute format('alter default privileges for role %I in schema public grant usage, select on sequences to %I', owner, app);
    execute format('alter default privileges for role %I in schema public grant execute on functions to %I', owner, app);
    execute format('revoke create on schema public from %I', app);
end \$\$;"

echo "Uygulama rolü hazır: $APP_USER (nosuperuser, nobypassrls)"
