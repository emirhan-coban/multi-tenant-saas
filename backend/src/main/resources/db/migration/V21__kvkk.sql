-- Faz 9 / KVKK: açık rıza kaydı, hesabın silinmesi (anonimleştirme) ve veri dışa aktarma.

-- ---------------------------------------------------------------------------
-- Açık rıza: hangi metnin hangi sürümünün ne zaman onaylandığı.
-- ---------------------------------------------------------------------------
alter table users add column consent_version varchar(16);
alter table users add column consent_at timestamptz;
alter table customer_accounts add column consent_version varchar(16);
alter table customer_accounts add column consent_at timestamptz;
alter table customer_accounts add column anonymized_at timestamptz;

-- Kanıt kaydı: kişisel veri içermez, yalnızca "ne zaman ne yapıldı".
create table kvkk_requests (
    id uuid primary key default gen_random_uuid(),
    subject_type varchar(16) not null check (subject_type in ('STAFF', 'CUSTOMER')),
    subject_id uuid not null,
    action varchar(16) not null check (action in ('EXPORT', 'DELETE')),
    created_at timestamptz not null default now(),
    affected jsonb not null default '{}'::jsonb
);

create index idx_kvkk_requests_subject on kvkk_requests(subject_id, created_at desc);

-- ---------------------------------------------------------------------------
-- Silme: randevu geçmişi ticari/muhasebe kaydı olarak kalır, kişisel alanlar silinir.
-- Müşteri birden çok işletmede randevu almış olabilir; bu yüzden tenant bağlamı olmadan
-- çalışan güvenli bir fonksiyon gerekir. Fonksiyon yalnızca verilen hesabın satırlarına
-- dokunur ve geriye sayı döndürür, veri döndürmez.
-- ---------------------------------------------------------------------------
create function anonymize_customer_appointments(p_account uuid)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare affected integer;
begin
    update appointments set customer_name = 'Silinmiş kullanıcı', customer_phone = null,
                            customer_email = null, email_notifications = false, notes = null
    where customer_id in (select id from tenant_customers where customer_account_id = p_account);
    get diagnostics affected = row_count;
    return affected;
end;
$$;

grant execute on function anonymize_customer_appointments(uuid) to public;

-- ---------------------------------------------------------------------------
-- Dışa aktarma: müşterinin kendi verisi, tek bir JSON belgesi olarak.
-- Yalnızca istekte bulunan hesaba ait satırlar döner; işletmelerin diğer verisi dönmez.
-- ---------------------------------------------------------------------------
create function export_customer_data(p_account uuid)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'hesap', (select jsonb_build_object(
                'eposta', email, 'adSoyad', full_name, 'telefon', phone,
                'olusturulma', created_at, 'epostaDogrulandi', email_verified_at,
                'acikRizaSurumu', consent_version, 'acikRizaTarihi', consent_at)
            from customer_accounts where id = p_account),
        'isletmeler', coalesce((select jsonb_agg(jsonb_build_object(
                'isletme', t.name, 'guvenDurumu', tc.trust_state,
                'tamamlanan', tc.completed_count, 'gelmedi', tc.no_show_count,
                'ilkKayit', tc.created_at))
            from tenant_customers tc join tenants t on t.id = tc.tenant_id
            where tc.customer_account_id = p_account), '[]'::jsonb),
        'randevular', coalesce((select jsonb_agg(jsonb_build_object(
                'isletme', t.name, 'baslangic', a.starts_at, 'bitis', a.ends_at,
                'durum', a.status, 'hizmet', s.name) order by a.starts_at desc)
            from appointments a
            join tenants t on t.id = a.tenant_id
            join services s on s.id = a.service_id
            where a.customer_id in (select id from tenant_customers where customer_account_id = p_account)), '[]'::jsonb),
        'bildirimAbonelikleri', coalesce((select count(*) from push_subscriptions
            where owner_type = 'CUSTOMER' and owner_id = p_account and disabled_at is null), 0)
    )
$$;

grant execute on function export_customer_data(uuid) to public;
