import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { Button } from '../components/ui';
import { cancelBookingRequest, createBookingRequest, getAvailability, getBookingRequest, getPublicScene, getPublicShop, type Availability, type PublicShop } from '../api/customer-booking';
import type { BookingRequest } from '../api/booking-contract';
import type { PublicSceneManifest } from '../api/shop-scene';
import { errorMessage } from '../api/errors';
import { useCustomerAuth } from '../store/customer-auth';

const BarberShopScene = lazy(() => import('../components/BarberShopScene').then(module => ({ default: module.BarberShopScene })));

function dateValue(offset = 1) {
  const value = new Date();
  value.setDate(value.getDate() + offset);
  return value.toISOString().slice(0, 10);
}

export function BookingScreen() {
  const { tenantSlug = '' } = useParams();
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { session } = useCustomerAuth();
  const [shop, setShop] = useState<PublicShop | null>(null);
  const [manifest, setManifest] = useState<PublicSceneManifest | null>(null);
  const [staffId, setStaffId] = useState(params.get('staff') ?? '');
  const [chairKey, setChairKey] = useState(params.get('chair') ?? '');
  const [view, setView] = useState<'3d' | 'list'>(params.get('view') === 'list' ? 'list' : '3d');
  const [serviceId, setServiceId] = useState(params.get('service') ?? '');
  const [date, setDate] = useState(params.get('date') ?? dateValue());
  const [slot, setSlot] = useState(params.get('slot') ?? '');
  const [availability, setAvailability] = useState<Availability | null>(null);
  const [request, setRequest] = useState<BookingRequest | null>(null);
  const [loading, setLoading] = useState(true);
  const [slotsLoading, setSlotsLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [remaining, setRemaining] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    getPublicShop(tenantSlug).then(data => {
      if (!active) return;
      setShop(data);
      setStaffId(current => current || data.staff[0]?.id || '');
      setServiceId(current => current || data.services[0]?.id || '');
    }).catch(reason => active && setError(errorMessage(reason, 'İşletme bilgileri yüklenemedi.')))
      .finally(() => active && setLoading(false));
    getPublicScene(tenantSlug).then(data => {
      if (!active) return;
      setManifest(data);
      setStaffId(current => {
        const mapped = data.chairs.find(chair => chair.staffId === current) ?? data.chairs[0];
        const requestedChair = params.get('chair');
        setChairKey(requestedChair && data.chairs.some(chair => chair.chairKey === requestedChair && chair.staffId === mapped?.staffId)
          ? requestedChair : mapped?.chairKey ?? '');
        return mapped?.staffId ?? current;
      });
    }).catch(() => active && setView('list'));
    return () => { active = false; };
  }, [tenantSlug]);

  useEffect(() => {
    if (!staffId || !serviceId || !date) return;
    let active = true;
    setSlotsLoading(true);
    setSlot(current => current && params.get('slot') === current ? current : '');
    getAvailability(tenantSlug, serviceId, staffId, date)
      .then(data => active && setAvailability(data))
      .catch(reason => active && setError(errorMessage(reason, 'Müsait saatler alınamadı.')))
      .finally(() => active && setSlotsLoading(false));
    return () => { active = false; };
  }, [tenantSlug, serviceId, staffId, date, params]);

  useEffect(() => {
    if (request?.status !== 'PENDING_APPROVAL') return;
    let active = true;
    const refresh = () => getBookingRequest(request.id).then(result => active && setRequest(result)).catch(() => undefined);
    void refresh();
    const timer = window.setInterval(refresh, 5_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [request?.id, request?.status]);

  useEffect(() => {
    if (!request?.cancellableUntil) { setRemaining(''); return; }
    const update = () => {
      const seconds = Math.max(0, Math.ceil((new Date(request.cancellableUntil!).getTime() - Date.now()) / 1000));
      setRemaining(`${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`);
    };
    update();
    const timer = window.setInterval(update, 1_000);
    return () => window.clearInterval(timer);
  }, [request?.cancellableUntil]);

  const selectedStaff = shop?.staff.find(item => item.id === staffId);
  const selectedService = shop?.services.find(item => item.id === serviceId);
  const formatter = useMemo(() => new Intl.DateTimeFormat('tr-TR', {
    timeZone: shop?.timezone ?? 'Europe/Istanbul', hour: '2-digit', minute: '2-digit',
  }), [shop?.timezone]);

  async function submit() {
    if (!slot || !staffId || !serviceId) return;
    setError('');
    if (!session) {
      const draft = new URLSearchParams({ staff: staffId, service: serviceId, date, slot, view });
      if (view === '3d' && chairKey) draft.set('chair', chairKey);
      navigate('/customer/login', { state: { from: `/book/${tenantSlug}?${draft}` } });
      return;
    }
    setSubmitting(true);
    try {
      setRequest(await createBookingRequest({ tenantSlug, staffId, serviceId, startsAt: slot,
        source: view === '3d' && manifest && chairKey ? 'WEB_3D' : 'WEB_LIST',
        ...(view === '3d' && manifest && chairKey ? { sceneVersion: manifest.version, chairKey } : {}),
      }));
    } catch (reason) {
      setError(errorMessage(reason, 'Randevu talebi oluşturulamadı. Müsait saatleri yenileyin.'));
    } finally {
      setSubmitting(false);
    }
  }

  function selectStaff(id: string, selectedChair?: string) {
    setStaffId(id);
    setChairKey(selectedChair ?? manifest?.chairs.find(chair => chair.staffId === id)?.chairKey ?? '');
  }

  async function cancelCurrent() {
    if (!request) return;
    setCancelling(true);
    setError('');
    try {
      const cancelled = await cancelBookingRequest(request);
      setRequest(current => current ? { ...current, status: 'CANCELLED', canCancel: false,
        cancellableUntil: null, holdExpiresAt: null, appointmentId: cancelled.appointmentId } : current);
    } catch (reason) {
      setError(errorMessage(reason, 'Rezervasyon iptal edilemedi. Hesabınızdan güncel durumu kontrol edin.'));
    } finally {
      setCancelling(false);
    }
  }

  if (loading) return <main className="booking-loading">Dükkân hazırlanıyor...</main>;
  if (!shop) return <main className="booking-loading"><p role="alert">{error || 'İşletme bulunamadı.'}</p></main>;
  if (request) {
    const confirmed = request.status === 'AUTO_CONFIRMED' || request.status === 'APPROVED';
    const automatic = request.status === 'AUTO_CONFIRMED' && request.resultType === 'APPOINTMENT';
    const rejected = request.status === 'REJECTED';
    const expired = request.status === 'EXPIRED';
    const cancelled = request.status === 'CANCELLED';
    return <main className={`booking-result booking-result-${request.status.toLowerCase()}`}>
    <div className="booking-result-mark">{confirmed ? '✓' : rejected || expired || cancelled ? '×' : '···'}</div>
    <p className="eyebrow">{shop.name}</p>
    <h1>{cancelled ? 'Rezervasyonun iptal edildi.' : confirmed ? 'Randevun kesinleşti.' : rejected ? 'Talebin onaylanmadı.' : expired ? 'Onay süresi doldu.' : 'İşletme onayı bekleniyor.'}</h1>
    <p>{selectedStaff?.displayName} ile {selectedService?.name}, {new Date(request.startsAt).toLocaleString('tr-TR', { timeZone: shop.timezone })}.</p>
    {automatic && <p className="booking-hold">Doğrulanmış müşteri hesabın sayesinde randevun otomatik onaylandı.</p>}
    {request.status === 'PENDING_APPROVAL' && request.holdExpiresAt && <p className="booking-hold">Talebin alındı; saat işletme onayına kadar ayrılmış sayılmaz. Kalan değerlendirme süresi <strong>{remaining}</strong>. Bu ekran otomatik güncellenir.</p>}
    {confirmed && request.cancellableUntil && <p className="booking-hold">Çevrim içi iptal sınırı: {new Date(request.cancellableUntil).toLocaleString('tr-TR', { timeZone: shop.timezone })}</p>}
    {request.canCancel && <button className="button button-quiet booking-cancel-button" disabled={cancelling} onClick={() => void cancelCurrent()}>{cancelling ? 'İptal ediliyor...' : 'Rezervasyonu iptal et'}</button>}
    {error && <p role="alert" className="form-error">{error}</p>}
    {(rejected || expired) && <button className="button button-primary" onClick={() => setRequest(null)}>Başka saat seç</button>}
    <Link className="text-link" to="/customer/account">Müşteri hesabına git</Link>
  </main>;
  }

  return <main className="booking-shell">
    <header className="booking-topbar">
      <div><p className="eyebrow">ÇEVRİM İÇİ RANDEVU</p><strong>{shop.name}</strong></div>
      <Link className="booking-account-link" to={session ? '/customer/account' : '/customer/login'}>{session ? session.customer.fullName : 'Giriş yap'}</Link>
    </header>
    <section className={`shop-stage shop-stage-${view}`} aria-label="Berber koltuğu seçimi">
      <div className="shop-stage-visual">
        {view === '3d' && manifest ? <Suspense fallback={<div className="barber-scene booking-loading">Sahne hazırlanıyor...</div>}>
          <BarberShopScene manifest={manifest} selectedId={staffId} onSelect={selectStaff} onFailure={() => setView('list')} />
        </Suspense> : <div className="shop-list-backdrop" aria-hidden="true"><span>RANDEVU</span></div>}
        <div className="shop-stage-copy"><p>{shop.timezone}</p><h1>{view === '3d' ? 'Koltuk seç.' : 'Berber seç.'}</h1><span>{selectedStaff ? `${selectedStaff.displayName} seçildi` : 'Bir seçim yap'}</span></div>
      </div>
      <aside className="booking-picker-panel">
        <div className="view-switch" role="group" aria-label="Berber seçim görünümü">
          <button type="button" className={view === '3d' ? 'is-active' : ''} disabled={!manifest} onClick={() => setView('3d')}>3D dükkân</button>
          <button type="button" className={view === 'list' ? 'is-active' : ''} onClick={() => setView('list')}>Liste</button>
        </div>
        <p className="eyebrow">01 · BERBER</p>
        <div className="chair-list">
          {shop.staff.map(person => {
            const chair = manifest?.chairs.find(item => item.staffId === person.id);
            return <button key={person.id} type="button" className={person.id === staffId ? 'chair-option is-selected' : 'chair-option'} onClick={() => selectStaff(person.id, chair?.chairKey)}>
              <span>{person.displayName.split(' ').map(part => part[0]).join('').slice(0, 2)}</span><strong>{person.displayName}</strong><small>{chair?.label ?? 'Liste seçimi'}</small>
            </button>;
          })}
        </div>
      </aside>
    </section>
    <section className="booking-workflow">
      <div className="booking-step">
        <p className="step-number">02</p><h2>Hizmet</h2>
        <div className="choice-grid">
          {shop.services.map(service => <button key={service.id} type="button" className={service.id === serviceId ? 'choice is-selected' : 'choice'} onClick={() => setServiceId(service.id)}>
            <strong>{service.name}</strong><span>{service.durationMin} dk · {Number(service.price).toLocaleString('tr-TR')} TL</span>
          </button>)}
        </div>
      </div>
      <div className="booking-step booking-time-step">
        <div><p className="step-number">03</p><h2>Tarih ve saat</h2></div>
        <label className="booking-date">Tarih<input type="date" min={dateValue(0)} max={dateValue(30)} value={date} onChange={event => setDate(event.target.value)} /></label>
        <div className="slot-grid" aria-live="polite">
          {slotsLoading ? <p className="muted">Saatler yükleniyor...</p> : availability?.slots.length
            ? availability.slots.map(item => <button type="button" key={item.startsAt} className={slot === item.startsAt ? 'slot is-selected' : 'slot'} onClick={() => setSlot(item.startsAt)}>{formatter.format(new Date(item.startsAt))}</button>)
            : <p className="muted">Bu gün için uygun saat bulunmuyor.</p>}
        </div>
      </div>
    </section>
    <footer className="booking-summary">
      <div><span>Seçimin</span><strong>{selectedStaff?.displayName || 'Berber seç'} · {selectedService?.name || 'Hizmet seç'}</strong></div>
      {error && <p role="alert" className="form-error">{error}</p>}
      <Button disabled={!slot || submitting} onClick={() => void submit()}>{submitting ? 'Onaylanıyor...' : session ? 'Randevuyu onayla' : 'Giriş yap ve devam et'}</Button>
    </footer>
  </main>;
}
