create table shop_scenes (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    version integer not null check (version > 0),
    status varchar(16) not null check (status in ('DRAFT','PUBLISHED','ARCHIVED')),
    template_key varchar(32) not null default 'CLASSIC_3_CHAIR'
        check (template_key in ('CLASSIC_3_CHAIR')),
    accent_color varchar(7) not null default '#245e50'
        check (accent_color ~ '^#[0-9a-fA-F]{6}$'),
    row_version integer not null default 0,
    created_by uuid references users(id),
    created_at timestamptz not null default now(),
    published_at timestamptz,
    unique (tenant_id, version)
);

create unique index uq_shop_scenes_draft on shop_scenes(tenant_id) where status='DRAFT';
create unique index uq_shop_scenes_published on shop_scenes(tenant_id) where status='PUBLISHED';

create table shop_chairs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id) on delete cascade,
    scene_id uuid not null references shop_scenes(id) on delete cascade,
    chair_key varchar(40) not null check (chair_key ~ '^[a-z0-9][a-z0-9-]{1,39}$'),
    label varchar(60) not null,
    staff_id uuid not null references staff(id),
    position_x numeric(5,2) not null check (position_x between -8 and 8),
    position_z numeric(5,2) not null check (position_z between -5 and 4),
    rotation_y numeric(6,3) not null default 0 check (rotation_y between -6.284 and 6.284),
    unique (scene_id, chair_key),
    unique (scene_id, staff_id),
    unique (id, tenant_id)
);

alter table shop_scenes enable row level security;
alter table shop_scenes force row level security;
create policy shop_scenes_tenant on shop_scenes
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

alter table shop_chairs enable row level security;
alter table shop_chairs force row level security;
create policy shop_chairs_tenant on shop_chairs
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

insert into shop_scenes(tenant_id, version, status, published_at)
select id, 1, 'PUBLISHED', now() from tenants;

insert into shop_chairs(tenant_id, scene_id, chair_key, label, staff_id, position_x, position_z, rotation_y)
select ranked.tenant_id, ranked.scene_id, 'chair-' || ranked.slot,
       ranked.display_name, ranked.staff_id, (ranked.slot - 2) * 3.25, -1.25, 0
from (
    select s.tenant_id, sc.id scene_id, s.id staff_id, s.display_name,
           row_number() over (partition by s.tenant_id order by s.display_name, s.id) slot
    from staff s join shop_scenes sc on sc.tenant_id=s.tenant_id and sc.status='PUBLISHED'
    where s.active=true
) ranked where ranked.slot <= 3;

alter table booking_requests add column source varchar(16) not null default 'WEB_LIST'
    check (source in ('WEB_LIST','WEB_3D'));
alter table booking_requests add column scene_version integer;
alter table booking_requests add column chair_key varchar(40);
alter table booking_requests add constraint booking_request_scene_source check (
    (source='WEB_LIST' and scene_version is null and chair_key is null)
    or (source='WEB_3D' and scene_version is not null and chair_key is not null)
);

alter table appointments add column source varchar(16) not null default 'STAFF'
    check (source in ('STAFF','WEB_LIST','WEB_3D'));

create function resolve_public_shop_scene(requested_slug text)
returns table(tenant_id uuid, scene_id uuid, scene_version integer, template_key varchar, accent_color varchar)
language sql stable security definer set search_path = public, pg_temp as $$
    select t.id, s.id, s.version, s.template_key, s.accent_color
    from tenants t join shop_scenes s on s.tenant_id=t.id and s.status='PUBLISHED'
    where lower(t.slug)=lower(requested_slug)
$$;

create function initialize_shop_scene_for_tenant()
returns trigger language plpgsql security definer set search_path = public, pg_temp as $$
begin
    insert into shop_scenes(tenant_id,version,status,published_at) values(new.id,1,'PUBLISHED',now());
    return new;
end $$;
create trigger trg_initialize_shop_scene after insert on tenants
for each row execute function initialize_shop_scene_for_tenant();

create function attach_staff_to_shop_scene()
returns trigger language plpgsql security definer set search_path = public, pg_temp as $$
declare target record; slot integer;
begin
    if not new.active then return new; end if;
    for target in select id,tenant_id from shop_scenes where tenant_id=new.tenant_id and status in ('PUBLISHED','DRAFT') loop
        select count(*) + 1 into slot from shop_chairs where scene_id=target.id;
        if slot <= 3 then
            insert into shop_chairs(tenant_id,scene_id,chair_key,label,staff_id,position_x,position_z,rotation_y)
            values(new.tenant_id,target.id,'chair-' || slot,new.display_name,new.id,(slot-2)*3.25,-1.25,0)
            on conflict do nothing;
        end if;
    end loop;
    return new;
end $$;
create trigger trg_attach_staff_to_shop_scene after insert on staff
for each row execute function attach_staff_to_shop_scene();
