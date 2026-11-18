-- Davetle gelen personel pasif eklenip kabulde UPDATE ile aktifleşir; eski tetikleyici yalnızca
-- INSERT'te çalıştığı için bu personele hiç koltuk bağlanmıyor ve 3D sahne boş kalıyordu.
-- Tetikleyici artık pasiften aktife geçişte de çalışır, boş (en küçük) koltuk numarasını seçer ve
-- zaten koltuğu olan personeli tekrar eklemez.
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
    if tg_op = 'UPDATE' and old.active then
        return new;
    end if;
    for target in
        select id, tenant_id
        from shop_scenes
        where tenant_id = new.tenant_id and status in ('PUBLISHED', 'DRAFT')
    loop
        if exists (select 1 from shop_chairs where scene_id = target.id and staff_id = new.id) then
            continue;
        end if;
        select min(n) into slot
        from generate_series(1, 3) n
        where not exists (select 1 from shop_chairs c where c.scene_id = target.id and c.chair_key = 'chair-' || n);
        if slot is not null then
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

drop trigger if exists trg_attach_staff_to_shop_scene on staff;
create trigger trg_attach_staff_to_shop_scene
after insert or update of active on staff
for each row execute function attach_staff_to_shop_scene();

-- Mevcut verilerin onarımı: aktif olduğu hâlde hiçbir koltuğa bağlı olmayan personeli
-- yayındaki ve taslak sahnelere (en fazla 3 koltuk) bağla.
do $$
declare scene record; person record; slot integer;
begin
    for scene in select id, tenant_id from shop_scenes where status in ('PUBLISHED', 'DRAFT') loop
        perform set_config('app.current_tenant', scene.tenant_id::text, true);
        for person in
            select id, tenant_id, display_name from staff
            where tenant_id = scene.tenant_id and active
            order by display_name, id
        loop
            if exists (select 1 from shop_chairs where scene_id = scene.id and staff_id = person.id) then
                continue;
            end if;
            select min(n) into slot
            from generate_series(1, 3) n
            where not exists (select 1 from shop_chairs c where c.scene_id = scene.id and c.chair_key = 'chair-' || n);
            if slot is not null then
                insert into shop_chairs(tenant_id, scene_id, chair_key, label, staff_id, position_x, position_z, rotation_y)
                values (person.tenant_id, scene.id, 'chair-' || slot, person.display_name, person.id, (slot - 2) * 3.25, -1.25, 0)
                on conflict do nothing;
            end if;
        end loop;
    end loop;
    perform set_config('app.current_tenant', '', true);
end;
$$;
