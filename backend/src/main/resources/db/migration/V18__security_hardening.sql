-- Faz 9: uygulamanın süper kullanıcı olmadan çalışabilmesi için gereken yardımcılar.
-- Uygulama rolü RLS'i atlayamaz; bu yüzden tüm tenant'ları dolaşan arka plan işleri
-- (outbox, saklama işi, metrikler) tenant listesini yalnızca bu güvenli fonksiyondan alır.
create or replace function ops_tenant_ids()
returns setof uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select id from tenants order by id
$$;

comment on function ops_tenant_ids() is
    'Arka plan işleri için tenant kimlikleri. Yalnızca kimlik döndürür; tenant verisi döndürmez.';

-- Faz 8''de eklenen kuyruk derinliği fonksiyonu da uygulama rolünden çağrılabilmeli.
grant execute on function ops_queue_depth() to public;
grant execute on function ops_tenant_ids() to public;
