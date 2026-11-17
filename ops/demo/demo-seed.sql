-- Demo işletmesini (3 berber, 3 hizmet, haftanın her günü 09:00-20:00) doldurur.
-- Idempotent: işletmede personel varsa hiçbir şey yapmaz.
-- Önce API üzerinden demo@berber.test kaydı yapılmış olmalıdır (seed-demo.sh bunu yapar).
-- Şema sahibi (owner) rolüyle çalıştırılır; uygulama rolü bu işlem için kullanılmaz.
do $$
declare t uuid; owner uuid; hash text; u uuid; s uuid; name text; d int;
begin
  select id into t from tenants where slug='demo-berber';
  select id,password_hash into owner,hash from users where email='demo@berber.test';
  if t is null or exists (select 1 from staff where tenant_id=t) then return; end if;
  update tenants set timezone='Europe/Istanbul', plan='PRO' where id=t;
  insert into services(tenant_id,name,duration_min,buffer_min,price) values
    (t,'Saç kesimi',45,0,600),(t,'Sakal tıraşı',30,0,350),(t,'Saç + sakal',60,10,850);
  foreach name in array array['Emre Usta','Ahmet Yılmaz','Can Demir'] loop
    if name = 'Emre Usta' then u := owner;
    else
      insert into users(email,password_hash,full_name) values (lower(split_part(name,' ',1))||'@berber.test',hash,name) returning id into u;
      insert into memberships(user_id,tenant_id,role) values (u,t,'STAFF');
    end if;
    insert into staff(tenant_id,user_id,display_name) values (t,u,name) returning id into s;
    for d in 0..6 loop
      insert into working_hours(tenant_id,staff_id,day_of_week,start_time,end_time) values (t,s,d,'09:00','20:00');
    end loop;
  end loop;
end $$;
