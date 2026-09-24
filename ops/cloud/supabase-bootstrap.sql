\set ON_ERROR_STOP on

-- Supabase shared pooler kullanici adindaki `.project-ref` eki yalnizca
-- baglanti yonlendirmesi icindir. PostgreSQL icindeki asil rol app_role'dur.
select format('create role %I login', :'app_role')
where not exists (select 1 from pg_roles where rolname = :'app_role')
\gexec

select format(
    -- Supabase'in postgres rolu SUPERUSER degildir. PostgreSQL, mevcut degeri false
    -- olsa bile SUPERUSER/BYPASSRLS seceneklerini yalnizca gercek superuser'in
    -- degistirmesine izin verir. Yeni roller bu iki yetkiyi varsayilan olarak almaz;
    -- bayraklar betigin sonundaki sorguyla ayrica dogrulanir.
    'alter role %I with login nocreatedb nocreaterole password %L',
    :'app_role',
    :'app_password'
)
\gexec

select format('grant connect on database %I to %I', current_database(), :'app_role')
\gexec
select format('grant usage on schema public to %I', :'app_role')
\gexec
select format('grant select, insert, update, delete on all tables in schema public to %I', :'app_role')
\gexec
select format('grant usage, select on all sequences in schema public to %I', :'app_role')
\gexec
-- Supabase'in yonettigi extension fonksiyonlari farkli rollere aittir. Yalnizca
-- migration owner'inin olusturdugu uygulama fonksiyonlarina yetki verilir.
select format(
    'grant execute on function %I.%I(%s) to %I',
    n.nspname,
    p.proname,
    pg_get_function_identity_arguments(p.oid),
    :'app_role'
)
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public'
  and p.proowner = (select oid from pg_roles where rolname = current_user)
\gexec

-- Flyway daha sonra yeni nesneler olusturdugunda uygulama rolu yetkileri otomatik alir.
select format(
    'alter default privileges for role %I in schema public grant select, insert, update, delete on tables to %I',
    current_user,
    :'app_role'
)
\gexec
select format(
    'alter default privileges for role %I in schema public grant usage, select on sequences to %I',
    current_user,
    :'app_role'
)
\gexec
select format(
    'alter default privileges for role %I in schema public grant execute on functions to %I',
    current_user,
    :'app_role'
)
\gexec

select format('revoke create on schema public from %I', :'app_role')
\gexec

select rolname, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls
from pg_roles
where rolname = :'app_role';
