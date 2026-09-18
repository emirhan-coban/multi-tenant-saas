create or replace function export_customer_data(p_account uuid)
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
        'rezervasyonTalepleri', coalesce((select jsonb_agg(jsonb_build_object(
                'isletme', t.name, 'baslangic', b.starts_at, 'durum', b.status,
                'not', b.note, 'olusturulma', b.created_at) order by b.created_at desc)
            from booking_requests b
            join tenants t on t.id = b.tenant_id
            where b.customer_id in (select id from tenant_customers where customer_account_id = p_account)), '[]'::jsonb),
        'bildirimler', coalesce((select jsonb_agg(jsonb_build_object(
                'olay', n.event_type, 'baslik', n.title, 'icerik', n.body,
                'olusturulma', n.created_at) order by n.created_at desc)
            from in_app_notifications n
            where n.recipient_type = 'CUSTOMER' and n.recipient_id = p_account), '[]'::jsonb),
        'bildirimAbonelikleri', coalesce((select count(*) from push_subscriptions
            where owner_type = 'CUSTOMER' and owner_id = p_account and disabled_at is null), 0)
    )
$$;

grant execute on function export_customer_data(uuid) to public;