-- Mantiksal veri geri yuklemesinde tenant/staff satirlari, dump'ta zaten bulunan
-- varsayilan ayar ve sahne satirlarini tekrar uretmemelidir. Normal uygulama akisinda
-- bu GUC tanimli degildir ve mevcut davranis aynen devam eder.
create or replace function create_default_booking_settings()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare previous text := coalesce(current_setting('app.current_tenant', true), '');
begin
    if current_setting('app.restore_mode', true) = 'on' then
        return new;
    end if;
    perform set_config('app.current_tenant', new.id::text, true);
    insert into booking_settings(tenant_id) values (new.id);
    perform set_config('app.current_tenant', previous, true);
    return new;
end;
$$;

create or replace function initialize_shop_scene_for_tenant()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if current_setting('app.restore_mode', true) = 'on' then
        return new;
    end if;
    insert into shop_scenes(tenant_id, version, status, published_at)
    values (new.id, 1, 'PUBLISHED', now());
    return new;
end;
$$;

create or replace function attach_staff_to_shop_scene()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare target record; slot integer;
begin
    if current_setting('app.restore_mode', true) = 'on' then
        return new;
    end if;
    if not new.active then
        return new;
    end if;
    for target in
        select id, tenant_id
        from shop_scenes
        where tenant_id = new.tenant_id and status in ('PUBLISHED', 'DRAFT')
    loop
        select count(*) + 1 into slot from shop_chairs where scene_id = target.id;
        if slot <= 3 then
            insert into shop_chairs(
                tenant_id, scene_id, chair_key, label, staff_id,
                position_x, position_z, rotation_y
            ) values (
                new.tenant_id, target.id, 'chair-' || slot, new.display_name,
                new.id, (slot - 2) * 3.25, -1.25, 0
            ) on conflict do nothing;
        end if;
    end loop;
    return new;
end;
$$;
