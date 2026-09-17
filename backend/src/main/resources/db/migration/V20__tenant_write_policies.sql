-- Faz 9: uygulama artık RLS'i atlayamayan bir rolle bağlandığı için `tenants`
-- tablosundaki eksik yazma politikaları ortaya çıktı. Süper kullanıcıyken bu sorun
-- görünmüyordu: V2'de yalnızca SELECT politikası vardı, dolayısıyla kısıtlı rolle
-- kayıt (yeni işletme oluşturma) ve işletme ayarlarını güncelleme engellenirdi.

-- Kayıt herkese açık bir uçtur; yeni işletme satırı her zaman oluşturulabilir.
-- Satır oluşturulduktan sonra yalnızca kendi bağlamında görülebilir ve güncellenebilir.
create policy tenant_insert on tenants
    for insert
    with check (true);

create policy tenant_update on tenants
    for update
    using (id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- Silme politikası bilinçli olarak yoktur: işletme silme yalnızca şema sahibi
-- (bakım/operasyon) tarafından yapılabilir.

-- V2'deki SELECT politikası boş bağlamda `''::uuid` dönüşüm hatası verebiliyordu;
-- diğer tablolarla aynı nullif kalıbına getirilir.
drop policy if exists tenant_isolation_select on tenants;
create policy tenant_isolation_select on tenants
    for select
    using (id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- V13'teki tetikleyici security definer'dır ama booking_settings FORCE RLS olduğu için
-- şema sahibi de politikaya tabidir. Kayıt anında henüz tenant bağlamı yoktur; tetikleyici
-- bağlamı kendi işlemi için kurar ve hemen geri alır.
create or replace function create_default_booking_settings()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare previous text := coalesce(current_setting('app.current_tenant', true), '');
begin
    perform set_config('app.current_tenant', new.id::text, true);
    insert into booking_settings(tenant_id) values (new.id);
    perform set_config('app.current_tenant', previous, true);
    return new;
end;
$$;

-- Kayıt sırasında benzersiz slug üretilir; `tenants` SELECT politikası kısıtlı role
-- tüm satırları göstermediği için kontrol güvenli fonksiyonla yapılır.
-- Yalnızca "bu slug alınmış mı" bilgisini döndürür, işletme verisi sızdırmaz.
create function tenant_slug_taken(p_slug text)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (select 1 from tenants where slug = p_slug)
$$;

grant execute on function tenant_slug_taken(text) to public;

-- Aynı kalıp farkı V2'den kalan diğer tablolarda da var.
drop policy if exists tenant_isolation_services on services;
create policy tenant_isolation_services on services
    for all
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

drop policy if exists tenant_isolation_staff on staff;
create policy tenant_isolation_staff on staff
    for all
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

drop policy if exists tenant_isolation_time_off on time_off;
create policy tenant_isolation_time_off on time_off
    for all
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

drop policy if exists tenant_isolation_working_hours on working_hours;
create policy tenant_isolation_working_hours on working_hours
    for all
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
