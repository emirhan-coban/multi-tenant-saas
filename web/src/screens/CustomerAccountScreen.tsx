import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { cancelBookingRequest, createVisitCode, downloadBookingCalendar, getCustomerBookings,
  getCustomerNotifications, getRescheduleAvailability, markCustomerNotificationRead, rescheduleBooking,
  type Availability } from '../api/customer-booking';
import type { CustomerBooking, CustomerBookings, InAppNotification } from '../api/booking-contract';
import { errorMessage } from '../api/errors';
import { useCustomerAuth } from '../store/customer-auth';
import { Button } from '../components/ui';
import { Modal } from '../components/Modal';
import { NotificationPermissionCard } from '../components/NotificationPermissionCard';
import { InstallPrompt } from '../components/InstallPrompt';
import { OfflineBanner } from '../components/OfflineBanner';
import { logoutEverywhere } from '../api/pwa';
import { disablePush } from '../pwa/push';
import { CustomerPrivacyPanel } from '../components/CustomerPrivacyPanel';

const statusText: Record<string, string> = {
  PENDING_APPROVAL: 'Onay bekliyor', AUTO_CONFIRMED: 'Kesinleşti', APPROVED: 'Kesinleşti',
  REJECTED: 'Onaylanmadı', EXPIRED: 'Süresi doldu', CANCELLED: 'İptal edildi',
  CONFIRMED: 'Kesinleşti', COMPLETED: 'Tamamlandı', NO_SHOW: 'Gidilmedi',
};

function effectiveStatus(item: CustomerBooking) {
  return item.appointmentStatus && item.appointmentStatus !== 'CONFIRMED' ? item.appointmentStatus : item.status;
}

function format(value: string, timezone: string) {
  return new Intl.DateTimeFormat('tr-TR', { timeZone: timezone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export function CustomerAccountScreen() {
  const { session, logout } = useCustomerAuth();
  const [data, setData] = useState<CustomerBookings | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<CustomerBooking | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [visitCode, setVisitCode] = useState<{ code: string; expiresAt: string; tenantName: string } | null>(null);
  const [creatingCode, setCreatingCode] = useState<string | null>(null);
  const [tab, setTab] = useState<'active' | 'history'>('active');
  const [moving, setMoving] = useState<CustomerBooking | null>(null);
  const [moveDate, setMoveDate] = useState('');
  const [moveSlots, setMoveSlots] = useState<Availability | null>(null);
  const [moveLoading, setMoveLoading] = useState(false);
  const [notifications, setNotifications] = useState<InAppNotification[]>([]);
  const [message, setMessage] = useState('');
  const [signingOut, setSigningOut] = useState(false);

  async function load(signal?: AbortSignal) {
    setError('');
    try { setData(await getCustomerBookings(signal)); }
    catch (reason) { if (!signal?.aborted) setError(errorMessage(reason, 'Randevular yüklenemedi.')); }
    finally { if (!signal?.aborted) setLoading(false); }
  }

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    void getCustomerNotifications(controller.signal).then(setNotifications).catch(() => undefined);
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        void load();
        void getCustomerNotifications().then(setNotifications).catch(() => undefined);
      }
    }, 45_000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, []);

  const bookings = useMemo(() => (data?.items ?? []).filter(item => {
    const status = effectiveStatus(item);
    const active = status === 'PENDING_APPROVAL' || ((status === 'AUTO_CONFIRMED' || status === 'APPROVED') && new Date(item.startsAt) > new Date());
    return tab === 'active' ? active : !active;
  }), [data, tab]);

  async function cancel() {
    if (!selected) return;
    setCancelling(true);
    try {
      await cancelBookingRequest(selected);
      setSelected(null);
      await load();
    } catch (reason) {
      setError(errorMessage(reason, 'Rezervasyon iptal edilemedi. Sayfayı yenileyip tekrar deneyin.'));
      setSelected(null);
    } finally { setCancelling(false); }
  }

  function localDate(item: CustomerBooking) {
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: item.timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(item.startsAt));
    const value = Object.fromEntries(parts.map(part => [part.type, part.value]));
    return `${value.year}-${value.month}-${value.day}`;
  }

  async function openMove(item: CustomerBooking) {
    const date = localDate(item);
    setMoving(item); setMoveDate(date); setMoveSlots(null); setMoveLoading(true); setError(''); setMessage('');
    try { setMoveSlots(await getRescheduleAvailability(item.id, date)); }
    catch (reason) { setError(errorMessage(reason, 'Müsait saatler yüklenemedi.')); }
    finally { setMoveLoading(false); }
  }

  async function changeMoveDate(date: string) {
    if (!moving || !date) return;
    setMoveDate(date); setMoveLoading(true); setMoveSlots(null); setError('');
    try { setMoveSlots(await getRescheduleAvailability(moving.id, date)); }
    catch (reason) { setError(errorMessage(reason, 'Müsait saatler yüklenemedi.')); }
    finally { setMoveLoading(false); }
  }

  async function move(startsAt: string) {
    if (!moving) return;
    setMoveLoading(true); setError('');
    try {
      const result = await rescheduleBooking(moving, startsAt);
      setMessage(result.rescheduleStatus === 'PENDING_APPROVAL'
        ? 'Taşıma talebin işletmeye iletildi. Eski randevun onay gelene kadar korunuyor.'
        : 'Randevun yeni saate taşındı.');
      setMoving(null); await load();
    } catch (reason) { setError(errorMessage(reason, 'Randevu taşınamadı. Eski randevun korunuyor.')); }
    finally { setMoveLoading(false); }
  }

  async function readNotification(item: InAppNotification) {
    if (item.readAt) return;
    try { const read = await markCustomerNotificationRead(item.id); setNotifications(list => list.map(value => value.id === read.id ? read : value)); }
    catch { /* Booking state remains the source of truth. */ }
  }

  async function showVisitCode(item: CustomerBooking) {
    setCreatingCode(item.id); setError('');
    try {
      const result = await createVisitCode(item.id);
      setVisitCode({ code: result.code, expiresAt: result.expiresAt, tenantName: item.tenantName });
    } catch (reason) { setError(errorMessage(reason, 'Ziyaret kodu şu anda oluşturulamadı. Randevu saatine yakın tekrar deneyin.')); }
    finally { setCreatingCode(null); }
  }

  async function signOut(everywhere: boolean) {
    setSigningOut(true);
    // Paylaşılan cihazda bir sonraki kişi bu hesabın bildirimlerini almasın.
    await disablePush('customer').catch(() => undefined);
    if (everywhere) {
      try { await logoutEverywhere('customer'); }
      catch (reason) { setError(errorMessage(reason, 'Diğer cihazlardaki oturumlar kapatılamadı. Tekrar deneyin.')); setSigningOut(false); return; }
    }
    logout();
  }

  if (!session) return null;
  const { customer } = session;
  return <main className="customer-account-shell">
    <header className="customer-account-header">
      <div><p className="eyebrow">MÜŞTERİ HESABI</p><h1>Merhaba, {customer.fullName}</h1><p>{customer.email}{customer.phone ? ` · ${customer.phone}` : ''}</p></div>
      <div className="customer-account-exit">
        <Button variant="quiet" disabled={signingOut} onClick={() => void signOut(false)}>Çıkış yap</Button>
        <button type="button" className="text-button" disabled={signingOut} onClick={() => void signOut(true)}>Tüm cihazlardan çıkış</button>
      </div>
    </header>
    <OfflineBanner />

    <section className="customer-summary" aria-label="Randevu özeti">
      <div><strong>{data?.summary.upcoming ?? 0}</strong><span>Yaklaşan</span></div>
      <div><strong>{data?.summary.pending ?? 0}</strong><span>Onay bekleyen</span></div>
      <div><strong>{data?.summary.completed ?? 0}</strong><span>Tamamlanan</span></div>
      <div><strong>{data?.summary.cancelled ?? 0}</strong><span>İptal</span></div>
      <div><strong>{data?.summary.noShow ?? 0}</strong><span>Gidilmeyen</span></div>
    </section>
    {message && <p className="success-notice" role="status">{message}</p>}
    <div className="customer-pwa-row">
      <NotificationPermissionCard audience="customer" />
      <InstallPrompt context="customer" />
    </div>
    {notifications.length > 0 && <section className="customer-notifications" aria-label="Bildirimler">
      <div><p className="eyebrow">GÜNCELLEMELER</p><h2>Bildirimler</h2></div>
      <div>{notifications.slice(0, 5).map(item => <button key={item.id} className={item.readAt ? 'is-read' : ''} onClick={() => void readNotification(item)}>
        <strong>{item.title}</strong><span>{item.body}</span><small>{new Date(item.createdAt).toLocaleString('tr-TR')}</small>
      </button>)}</div>
    </section>}

    <section className="customer-bookings-section">
      <div className="customer-bookings-heading">
        <div><p className="eyebrow">RANDEVULARIN</p><h2>{tab === 'active' ? 'Yaklaşanlar' : 'Geçmiş'}</h2></div>
        <div className="customer-tabs" role="tablist" aria-label="Randevu görünümü">
          <button role="tab" aria-selected={tab === 'active'} onClick={() => setTab('active')}>Aktif</button>
          <button role="tab" aria-selected={tab === 'history'} onClick={() => setTab('history')}>Geçmiş</button>
        </div>
      </div>
      {error && <div className="error-notice" role="alert"><p>{error}</p><Button variant="quiet" onClick={() => void load()}>Tekrar dene</Button></div>}
      {loading ? <p className="customer-empty">Randevular yükleniyor...</p> : bookings.length === 0 ? <div className="customer-empty">
        <h3>{tab === 'active' ? 'Aktif randevun yok' : 'Henüz geçmiş kayıt yok'}</h3>
        <p>{tab === 'active' ? 'İşletmenin rezervasyon bağlantısından koltuğunu ve saatini seçebilirsin.' : 'Tamamlanan ve iptal edilen randevular burada görünür.'}</p>
      </div> : <div className="customer-booking-list">
        {bookings.map(item => {
          const status = effectiveStatus(item);
          return <article className="customer-booking-row" key={item.id}>
            <time dateTime={item.startsAt}><strong>{new Intl.DateTimeFormat('tr-TR', { timeZone: item.timezone, day: '2-digit' }).format(new Date(item.startsAt))}</strong><span>{new Intl.DateTimeFormat('tr-TR', { timeZone: item.timezone, month: 'short' }).format(new Date(item.startsAt))}</span></time>
            <div className="customer-booking-copy"><p>{item.tenantName}</p><h3>{item.serviceName}</h3><span>{item.staffName} · {format(item.startsAt, item.timezone)}</span>{item.rejectionReason && <small>{item.rejectionReason}</small>}
              {item.rescheduleStatus === 'PENDING_APPROVAL' && <small>Yeni saat talebi: {item.proposedStartsAt ? format(item.proposedStartsAt, item.timezone) : ''}. Eski randevun korunuyor.</small>}
              {item.rescheduleStatus === 'NEEDS_RESELECTION' && <small>İstenen saat uygun değil. Eski randevun korundu; yeni bir saat seçebilirsin.</small>}
              {status === 'NO_SHOW' && <small>İşletme bu ziyareti “gelmedi” olarak işaretledi. Sonraki rezervasyonun yeniden onay isteyebilir.</small>}
              {status === 'CANCELLED' && <small>Bu kayıt iptal edildi ve artık takvimde yer tutmuyor.</small>}
            </div>
            <div className="customer-booking-actions"><span className={`customer-status status-${status.toLowerCase()}`}>{statusText[status]}</span>
              {item.appointmentStatus === 'CONFIRMED' && <Button variant="quiet" disabled={creatingCode === item.id} onClick={() => void showVisitCode(item)}>{creatingCode === item.id ? 'Hazırlanıyor…' : 'Ziyaret kodu'}</Button>}
              {item.appointmentStatus === 'CONFIRMED' && <Button variant="quiet" onClick={() => void downloadBookingCalendar(item.id)}>Takvime ekle</Button>}
              {item.canReschedule && item.rescheduleStatus !== 'PENDING_APPROVAL' && <Button variant="quiet" onClick={() => void openMove(item)}>Taşı</Button>}
              {item.canCancel && <Button variant="quiet" onClick={() => setSelected(item)}>İptal et</Button>}</div>
          </article>;
        })}
      </div>}
    </section>
    <CustomerPrivacyPanel />
    <footer className="customer-account-footer"><span>Son 100 rezervasyon gösterilir.</span><Link to="/">Ana sayfaya dön</Link></footer>

    {selected && <Modal title="Rezervasyonu iptal et" busy={cancelling} onClose={() => setSelected(null)}>
      <p className="modal-description"><strong>{selected.tenantName}</strong> için {format(selected.startsAt, selected.timezone)} tarihli randevu iptal edilecek. Bu işlem geri alınamaz.</p>
      <div className="modal-actions"><Button variant="quiet" disabled={cancelling} onClick={() => setSelected(null)}>Vazgeç</Button><button className="button button-danger" disabled={cancelling} onClick={() => void cancel()}>{cancelling ? 'İptal ediliyor...' : 'Randevuyu iptal et'}</button></div>
    </Modal>}
    {moving && <Modal title="Randevuyu taşı" busy={moveLoading} onClose={() => setMoving(null)}>
      <p className="modal-description">Yeni saat dolarsa veya işletme talebi onaylamazsa <strong>{format(moving.startsAt, moving.timezone)}</strong> tarihli mevcut randevun korunur.</p>
      <label className="field">Yeni tarih<input type="date" value={moveDate} disabled={moveLoading} onChange={event => void changeMoveDate(event.target.value)} /></label>
      {moveLoading && <p role="status">Müsait saatler yükleniyor...</p>}
      {moveSlots && <div className="customer-move-slots">{moveSlots.slots.map(slot => <button key={slot.startsAt} disabled={moveLoading} onClick={() => void move(slot.startsAt)}>{new Intl.DateTimeFormat('tr-TR', { timeZone: moving.timezone, hour: '2-digit', minute: '2-digit' }).format(new Date(slot.startsAt))}</button>)}</div>}
      {moveSlots && moveSlots.slots.length === 0 && <p>Bu tarihte uygun saat yok.</p>}
      <div className="modal-actions"><Button variant="quiet" disabled={moveLoading} onClick={() => setMoving(null)}>Vazgeç</Button></div>
    </Modal>}
    {visitCode && <Modal title="Ziyaret kodun" onClose={() => setVisitCode(null)}>
      <div className="visit-code-panel"><p className="eyebrow">{visitCode.tenantName}</p><strong aria-label="6 haneli ziyaret kodu">{visitCode.code}</strong><p>Bu kodu yalnızca randevu sırasında berberine göster. Kod hesabını bu ziyaretle eşleştirir; giriş veya şifre değiştirme yetkisi vermez.</p><small>{new Date(visitCode.expiresAt).toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' })} saatine kadar geçerli</small></div>
      <div className="modal-actions"><Button onClick={() => setVisitCode(null)}>Tamam</Button></div>
    </Modal>}
  </main>;
}
