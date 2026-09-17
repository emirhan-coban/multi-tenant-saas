import { useEffect, useState } from 'react';
import { Button } from '../components/ui';
import { errorMessage } from '../api/errors';
import type { BookingCustomer } from '../api/types';
import type { CustomerTrustState } from '../api/booking-contract';
import { useBookingCustomers, useCustomerTrustEvents, useUpdateCustomerTrust } from '../query/management';

const labels: Record<CustomerTrustState, string> = {
  NEW: 'İlk ziyaret', TRUSTED: 'Güvenilir', REVIEW_REQUIRED: 'İncelenmeli', BLOCKED: 'Engelli',
};

function format(value: string | null) {
  return value ? new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : 'Henüz yok';
}

export function CustomersScreen() {
  const customers = useBookingCustomers();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = customers.data?.find(customer => customer.customerId === selectedId) ?? null;
  const events = useCustomerTrustEvents(selectedId);
  const update = useUpdateCustomerTrust();
  const [state, setState] = useState<CustomerTrustState>('TRUSTED');
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState('');
  const [failure, setFailure] = useState('');

  useEffect(() => {
    if (selected) { setState(selected.trustState === 'NEW' ? 'TRUSTED' : selected.trustState); setReason(''); }
  }, [selected?.customerId, selected?.trustState]);

  async function save(customer: BookingCustomer) {
    setNotice(''); setFailure('');
    try {
      await update.mutateAsync({ customerId: customer.customerId, trustState: state, expectedVersion: customer.version, reason: reason.trim() });
      setNotice(`${customer.customerName} için güven durumu güncellendi.`);
      setReason('');
    } catch (error) { setFailure(errorMessage(error, 'Güven durumu güncellenemedi. Listeyi yenileyin.')); }
  }

  return <section className="customers-page">
    <div className="page-heading"><p className="eyebrow">MÜŞTERİ GÜVENİ</p><h1>Müşteriler</h1><p>Tamamlanan ziyaretleri, gelinmeyen randevuları ve güven kararlarının kanıtını tek yerde inceleyin.</p></div>
    {notice && <p className="success-notice" role="status">{notice}</p>}
    {failure && <p className="error-notice" role="alert">{failure}</p>}
    {customers.isPending && <div className="services-skeleton" aria-label="Müşteriler yükleniyor">{[1, 2, 3].map(row => <div className="skeleton" key={row} />)}</div>}
    {customers.isError && <div className="error-notice"><p role="alert">Müşteriler yüklenemedi.</p><Button onClick={() => void customers.refetch()}>Tekrar dene</Button></div>}
    {customers.isSuccess && !customers.data.length && <section className="empty-state"><h2>Henüz müşteri yok</h2><p>İlk çevrim içi rezervasyon talebi geldiğinde müşteri burada görünecek.</p></section>}
    {!!customers.data?.length && <div className="customers-layout">
      <div className="customer-directory" role="list" aria-label="Müşteriler">
        {customers.data.map(customer => <button type="button" role="listitem" key={customer.customerId} className={selectedId === customer.customerId ? 'is-selected' : ''} onClick={() => setSelectedId(customer.customerId)}>
          <span className="customer-directory-name"><strong>{customer.customerName}</strong><small>{customer.customerPhone || customer.customerEmail}</small></span>
          <span className={`trust-badge trust-${customer.trustState.toLowerCase()}`}>{labels[customer.trustState]}</span>
          <span className="customer-directory-stats">{customer.completedCount} tamamlandı · {customer.noShowCount} gelmedi</span>
        </button>)}
      </div>
      <section className="customer-profile" aria-live="polite">
        {!selected && <div className="customer-profile-empty"><h2>Bir müşteri seçin</h2><p>Güven geçmişi ve işlem seçenekleri burada açılır.</p></div>}
        {selected && <>
          <div className="customer-profile-heading"><div><p className="eyebrow">MÜŞTERİ PROFİLİ</p><h2>{selected.customerName}</h2><p>{selected.customerEmail}<br />{selected.customerPhone || 'Telefon belirtilmedi'}</p></div><span className={`trust-badge trust-${selected.trustState.toLowerCase()}`}>{labels[selected.trustState]}</span></div>
          <dl className="customer-profile-facts"><div><dt>Tamamlanan</dt><dd>{selected.completedCount}</dd></div><div><dt>Gelinmeyen</dt><dd>{selected.noShowCount}</dd></div><div><dt>Son randevu</dt><dd>{format(selected.lastAppointmentAt)}</dd></div><div><dt>Güven kanıtı</dt><dd>{selected.evidenceAppointmentId ? 'Doğrulanmış ziyaret' : selected.trustReason || 'Henüz yok'}</dd></div></dl>
          <div className="customer-trust-form"><h3>Güven durumunu değiştir</h3><p>Manuel kararlar gerekçeli ve işlem geçmişinde görünür olur.</p><label className="field">Yeni durum<select value={state} onChange={event => setState(event.target.value as CustomerTrustState)}><option value="TRUSTED">Güvenilir</option><option value="REVIEW_REQUIRED">İncelenmeli</option><option value="BLOCKED">Engelli</option></select></label><label className="field">Gerekçe<input value={reason} maxLength={250} onChange={event => setReason(event.target.value)} /></label><Button disabled={update.isPending || state === selected.trustState || !reason.trim()} onClick={() => void save(selected)}>{update.isPending ? 'Kaydediliyor…' : 'Kararı kaydet'}</Button></div>
          <div className="trust-timeline"><h3>Güven geçmişi</h3>{events.isPending && <p>Geçmiş yükleniyor…</p>}{events.isError && <p role="alert">Güven geçmişi yüklenemedi.</p>}{events.data?.length === 0 && <p className="muted">Henüz bir güven geçişi kaydedilmedi.</p>}{events.data?.map(event => <article key={event.id}><span>{format(event.createdAt)}</span><strong>{labels[event.fromState]} → {labels[event.toState]}</strong><p>{event.reason} · {event.actorName}</p>{event.evidenceAppointmentId && <small>Kanıt: doğrulanmış tamamlanmış ziyaret</small>}</article>)}</div>
        </>}
      </section>
    </div>}
  </section>;
}
