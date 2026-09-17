import { useEffect, useState } from 'react';
import { Button } from '../components/ui';
import { errorMessage } from '../api/errors';
import type { BookingApprovalRequest } from '../api/types';
import type { StaffRescheduleRequest } from '../api/types';
import type { CustomerTrustState } from '../api/booking-contract';
import { useApproveBookingRequest, useApproveRescheduleRequest, useBookingRequests, useRejectBookingRequest,
  useRejectRescheduleRequest, useRescheduleRequests, useUpdateCustomerTrust } from '../query/management';

const trustLabels: Record<CustomerTrustState, string> = {
  NEW: 'İlk ziyaret', TRUSTED: 'Güvenilir', REVIEW_REQUIRED: 'İncelenmeli', BLOCKED: 'Engelli',
};

function HoldTime({ until }: { until: string | null }) {
  const [, tick] = useState(0);
  useEffect(() => {
    if (!until) return;
    const timer = window.setInterval(() => tick(value => value + 1), 1_000);
    return () => window.clearInterval(timer);
  }, [until]);
  if (!until) return <span className="hold-expired">Süre doldu</span>;
  const seconds = Math.max(0, Math.floor((new Date(until).getTime() - Date.now()) / 1_000));
  return <span className={seconds ? 'hold-time' : 'hold-expired'}>{seconds ? `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} kaldı` : 'Süre doldu'}</span>;
}

function RescheduleCard({ row, busy, onApprove, onReject }: { row: StaffRescheduleRequest; busy: boolean; onApprove: () => void; onReject: () => void }) {
  const format = (value: string) => new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Istanbul' }).format(new Date(value));
  return <article className="approval-card reschedule-card"><div className="approval-main"><div className="approval-title"><div className="customer-initials" aria-hidden="true">{row.customerName.slice(0, 1)}</div><div><h2>{row.customerName}</h2><p>{row.customerEmail}</p></div></div>
    <dl className="approval-details"><div><dt>Mevcut randevu</dt><dd>{format(row.currentStartsAt)}</dd></div><div><dt>İstenen saat</dt><dd>{format(row.requestedStartsAt)}</dd></div><div><dt>Berber</dt><dd>{row.staffName}</dd></div><div><dt>Hizmet</dt><dd>{row.serviceName}</dd></div></dl>
    <p className="muted">Onaya kadar mevcut randevu korunur. Onay anında yeni saat yeniden kontrol edilir.</p></div>
    <aside className="approval-actions"><Button disabled={busy} onClick={onApprove}>Taşımayı onayla</Button><Button variant="quiet" disabled={busy} onClick={onReject}>Reddet</Button></aside></article>;
}

function RequestCard({ row, busy, onApprove, onReject, onTrust }: {
  row: BookingApprovalRequest; busy: boolean;
  onApprove: () => void; onReject: (reason: string) => void;
  onTrust: (state: CustomerTrustState, reason: string) => void;
}) {
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [trust, setTrust] = useState<CustomerTrustState>(row.trustState);
  const [trustReason, setTrustReason] = useState('');
  const date = new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Istanbul' }).format(new Date(row.startsAt));
  return <article className="approval-card">
    <div className="approval-main">
      <div className="approval-title"><div className="customer-initials" aria-hidden="true">{row.customerName.split(' ').map(part => part[0]).join('').slice(0, 2)}</div><div><h2>{row.customerName}</h2><p>{row.customerPhone || row.customerEmail}</p></div></div>
      <span className={`trust-badge trust-${row.trustState.toLowerCase()}`}>{trustLabels[row.trustState]}</span>
      <dl className="approval-details"><div><dt>Randevu</dt><dd>{date}</dd></div><div><dt>Hizmet</dt><dd>{row.serviceName}</dd></div><div><dt>Berber</dt><dd>{row.staffName}</dd></div></dl>
      {row.note && <p className="request-note">“{row.note}”</p>}
      <div className="customer-history"><strong>{row.completedCount}</strong> tamamlanan <span>·</span> <strong>{row.noShowCount}</strong> gelinmeyen randevu</div>
    </div>
    <aside className="approval-actions">
      <HoldTime until={row.holdExpiresAt} />
      {!rejecting ? <><Button disabled={busy || !row.holdExpiresAt || row.trustState === 'BLOCKED'} onClick={onApprove}>Onayla</Button><Button variant="quiet" disabled={busy} onClick={() => setRejecting(true)}>Reddet</Button></> : <div className="reject-box"><label className="field">Red nedeni<textarea value={reason} maxLength={250} onChange={event => setReason(event.target.value)} /></label><div><Button variant="quiet" disabled={busy} onClick={() => setRejecting(false)}>Vazgeç</Button><Button className="button button-danger" disabled={busy} onClick={() => onReject(reason)}>Talebi reddet</Button></div></div>}
      <details className="trust-editor"><summary>Güven durumunu düzenle</summary><label className="field">Durum<select value={trust} onChange={event => setTrust(event.target.value as CustomerTrustState)}>{row.trustState === 'NEW' && <option value="NEW" disabled>İlk ziyaret</option>}<option value="TRUSTED">Güvenilir</option><option value="REVIEW_REQUIRED">İncelenmeli</option><option value="BLOCKED">Engelli</option></select></label><label className="field">Değişiklik gerekçesi<input value={trustReason} maxLength={250} onChange={event => setTrustReason(event.target.value)} /></label><Button variant="quiet" disabled={busy || trust === row.trustState || !trustReason.trim()} onClick={() => onTrust(trust, trustReason)}>Durumu kaydet</Button></details>
    </aside>
  </article>;
}

export function BookingRequestsScreen() {
  const list = useBookingRequests();
  const approve = useApproveBookingRequest();
  const reject = useRejectBookingRequest();
  const trust = useUpdateCustomerTrust();
  const reschedules = useRescheduleRequests();
  const approveMove = useApproveRescheduleRequest();
  const rejectMove = useRejectRescheduleRequest();
  const [notice, setNotice] = useState('');
  const [failure, setFailure] = useState('');
  const busy = approve.isPending || reject.isPending || trust.isPending || approveMove.isPending || rejectMove.isPending;
  async function perform(action: () => Promise<unknown>, message: string) {
    setFailure(''); setNotice('');
    try { await action(); setNotice(message); }
    catch (reason) { setFailure(errorMessage(reason, 'İşlem tamamlanamadı. Listeyi yenileyip tekrar deneyin.')); }
  }
  return <div className="approval-page">
    <div className="page-heading"><p className="eyebrow">RANDEVU AKIŞI</p><h1>Onay bekleyenler</h1><p>İlk ziyaret ve taşıma taleplerini inceleyin. Bekleyen talepler takvimde saat ayırmaz.</p></div>
    <div className="approval-toolbar"><span><strong>{(list.data?.length ?? 0) + (reschedules.data?.length ?? 0)}</strong> bekleyen işlem</span><Button variant="quiet" disabled={list.isFetching || reschedules.isFetching} onClick={() => { void list.refetch(); void reschedules.refetch(); }}>{list.isFetching || reschedules.isFetching ? 'Yenileniyor…' : 'Listeyi yenile'}</Button></div>
    {notice && <p className="success-notice" role="status">{notice}</p>}
    {failure && <p className="error-notice" role="alert">{failure}</p>}
    {list.isPending && <div className="services-skeleton" aria-label="Randevu talepleri yükleniyor" aria-busy="true">{[1, 2, 3].map(item => <div className="skeleton" key={item} />)}</div>}
    {list.isError && <div className="error-notice"><p role="alert">{errorMessage(list.error, 'Randevu talepleri yüklenemedi.')}</p><Button variant="quiet" onClick={() => void list.refetch()}>Tekrar dene</Button></div>}
    {list.isSuccess && !list.data.length && <section className="empty-state"><span className="empty-symbol" aria-hidden="true">✓</span><h2>Bekleyen talep yok</h2><p>Yeni bir ilk ziyaret talebi geldiğinde burada görünecek.</p></section>}
    <section className="approval-list" aria-label="Onay bekleyen randevu talepleri">{list.data?.map(row => <RequestCard key={row.id} row={row} busy={busy}
      onApprove={() => void perform(() => approve.mutateAsync({ id: row.id }), `${row.customerName} için randevu onaylandı.`)}
      onReject={reason => void perform(() => reject.mutateAsync({ id: row.id, reason }), 'Randevu talebi reddedildi.')}
      onTrust={(trustState, reason) => void perform(() => trust.mutateAsync({ customerId: row.customerId, trustState, expectedVersion: row.trustVersion, reason }), 'Müşteri güven durumu güncellendi.')} />)}</section>
    <section className="approval-list reschedule-list" aria-label="Onay bekleyen taşıma talepleri"><div className="section-heading"><p className="eyebrow">TAŞIMA TALEPLERİ</p><h2>Yeni saat isteyenler</h2></div>
      {reschedules.isPending && <p role="status">Taşıma talepleri yükleniyor...</p>}
      {reschedules.isError && <p role="alert" className="error-notice">{errorMessage(reschedules.error, 'Taşıma talepleri yüklenemedi.')}</p>}
      {reschedules.isSuccess && !reschedules.data.length && <p className="muted">Bekleyen taşıma talebi yok.</p>}
      {reschedules.data?.map(row => <RescheduleCard key={row.id} row={row} busy={busy}
        onApprove={() => void perform(() => approveMove.mutateAsync({ id: row.id }), `${row.customerName} için yeni saat kontrol edildi.`)}
        onReject={() => void perform(() => rejectMove.mutateAsync({ id: row.id, reason: 'İşletme tarafından uygun bulunmadı.' }), 'Taşıma talebi reddedildi; eski randevu korundu.')} />)}
    </section>
  </div>;
}
