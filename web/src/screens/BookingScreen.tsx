import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import axios from 'axios';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { cancelBookingRequest, createBookingRequest, getAvailability, getBookingRequest, getPublicScene, getPublicShop, getShopFeatures, type Availability, type PublicShop, type ShopFeatures } from '../api/customer-booking';
import { OfflineBanner } from '../components/OfflineBanner';
import { InstallPrompt } from '../components/InstallPrompt';
import { useOnlineStatus } from '../pwa/useOnlineStatus';
import type { BookingRequest } from '../api/booking-contract';
import type { PublicSceneManifest } from '../api/shop-scene';
import { errorMessage } from '../api/errors';
import { useCustomerAuth } from '../store/customer-auth';
import { initials, type ChairStatus } from '../components/scene-helpers';
import { useCompactLayout, useKeyboardInset, useStageHistory } from '../components/booking/hooks';
import { ServiceChips } from '../components/booking/ServiceChips';
import { BarberStrip } from '../components/booking/BarberStrip';
import { BookingSheet, type SheetState } from '../components/booking/BookingSheet';
import { BookingVitalsRecorder } from '../booking/vitals';

import '../booking.css';

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
  const [availabilityByStaff, setAvailabilityByStaff] = useState<Record<string, Availability>>({});
  const [step, setStep] = useState<1 | 2 | 3>(() => params.get('step') === '2' && params.get('slot') ? 2 : 1);
  const [note, setNote] = useState('');
  const [request, setRequest] = useState<BookingRequest | null>(null);
  const [loading, setLoading] = useState(true);
  const [slotsLoading, setSlotsLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [remaining, setRemaining] = useState('');
  const [error, setError] = useState('');
  const [features, setFeatures] = useState<ShopFeatures | null>(null);
  const [threeDOff, setThreeDOff] = useState(false);
  // 3D kodu ve modeli, kill switch cevabı gelmeden indirilmez (yavaş bayrak isteği yarışını önler).
  const [featuresReady, setFeaturesReady] = useState(false);
  const [notice, setNotice] = useState('');
  const online = useOnlineStatus();
  const panelHeading = useRef<HTMLHeadingElement>(null);
  const firstStepRender = useRef(true);
  const threeDOffRef = useRef(false);

  // Faz 10 — dar ekranda aşamalı akış. Masaüstü düzeni değişmez.
  const compact = useCompactLayout();
  useKeyboardInset();
  const [mobileStage, setMobileStage] = useState<'barber' | 'time'>('barber');
  const [sheet, setSheet] = useState<SheetState>('peek');
  // Koltuk seçildikten sonra canvas sökülür; yerine seçim anında yakalanan kare konur.
  const [frozen, setFrozen] = useState<string | null>(null);
  const capture = useRef<(() => string | null) | null>(null);
  const [sceneReady, setSceneReady] = useState(false);
  const vitals = useRef<BookingVitalsRecorder | null>(null);
  if (!vitals.current) vitals.current = new BookingVitalsRecorder();

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
    // Bayrak isteği başarısız olursa (eski sunucu, ağ) mevcut davranış korunur: sahne isteği karar verir.
    Promise.resolve().then(() => getShopFeatures(tenantSlug)).then(data => {
      if (!active || !data) return;
      setFeatures(data);
      if (data.threeD === false) disableThreeD('');
    }).catch(() => undefined).finally(() => active && setFeaturesReady(true));
    getPublicScene(tenantSlug).then(data => {
      if (!active) return;
      setManifest(threeDOffRef.current ? null : data);
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
    const recorder = vitals.current!;
    recorder.start(compact);
    return () => recorder.stop();
    // Yalnızca ilk montajda başlar; genişlik değişimi ölçümü yeniden başlatmaz.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Liste/şerit, model inmeden seçim yapılabilir hâle geldiği anda işaretlenir.
  useEffect(() => {
    if (shop?.staff.length) vitals.current?.listSelectable();
  }, [shop?.staff.length]);

  useEffect(() => { vitals.current?.view(view); }, [view]);

  // Akış tamamlandı: ölçüm sayfa kapanmasını beklemeden gönderilir.
  useEffect(() => { if (request) vitals.current?.send(); }, [request?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // Geri tuşu akışta bir aşama geri alır.
  const depth = !compact ? 0 : step === 1 ? (mobileStage === 'barber' ? 0 : 1) : step === 2 ? 2 : 3;
  useStageHistory(depth, target => {
    if (target <= 0) { setStep(1); setMobileStage('barber'); setSheet('peek'); setFrozen(null); return; }
    if (target === 1) { setStep(1); setMobileStage('time'); setSheet('expanded'); return; }
    setStep(target === 2 ? 2 : 3);
  });

  // Adım değişince klavye ve ekran okuyucu kullanıcısı panelin başına taşınır.
  useEffect(() => {
    if (firstStepRender.current) { firstStepRender.current = false; return; }
    panelHeading.current?.focus();
  }, [step]);

  const staffKey = shop?.staff.map(person => person.id).join(',') ?? '';
  useEffect(() => {
    if (!serviceId || !date || !staffKey) return;
    let active = true;
    setSlotsLoading(true);
    setAvailabilityByStaff({});
    // Sahnedeki her koltuk için ilk müsait saati göstermek üzere tüm berberler paralel sorgulanır.
    Promise.allSettled(staffKey.split(',').map(id => getAvailability(tenantSlug, serviceId, id, date).then(data => [id, data] as const)))
      .then(results => {
        if (!active) return;
        const map: Record<string, Availability> = {};
        results.forEach(result => { if (result.status === 'fulfilled') map[result.value[0]] = result.value[1]; });
        setAvailabilityByStaff(map);
        if (results.every(result => result.status === 'rejected')) setError('Müsait saatler alınamadı.');
      })
      .finally(() => active && setSlotsLoading(false));
    return () => { active = false; };
  }, [tenantSlug, serviceId, date, staffKey]);

  useEffect(() => {
    setSlot(current => current && params.get('slot') === current ? current : '');
  }, [serviceId, staffId, date, params]);

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
  const availability = availabilityByStaff[staffId] ?? null;
  const statuses = useMemo(() => {
    const map: Record<string, ChairStatus> = {};
    shop?.staff.forEach(person => {
      const data = availabilityByStaff[person.id];
      map[person.id] = !data ? { state: 'loading' }
        : data.slots.length ? { state: 'available', firstSlot: formatter.format(new Date(data.slots[0].startsAt)) } : { state: 'full' };
    });
    return map;
  }, [shop?.staff, availabilityByStaff, formatter]);

  // Hook'lar erken return'lerden ÖNCE çağrılmalı: aşağıda yükleme/sonuç ekranları
  // için erken çıkışlar var, bu yüzden sıraya bağlı her şey burada durur.
  const chairNumbers = useMemo(() => {
    const map: Record<string, string> = {};
    manifest?.chairs.forEach((chair, index) => { map[chair.staffId] = String(index + 1).padStart(2, '0'); });
    return map;
  }, [manifest?.chairs]);
  const sceneReadyHandler = useCallback(() => {
    setSceneReady(true);
    const quality = document.querySelector<HTMLElement>('.barber-scene')?.dataset.quality;
    vitals.current?.sceneSelectable(quality === 'high' || quality === 'low' ? quality : 'unknown');
  }, []);
  const captureHandler = useCallback((fn: () => string | null) => { capture.current = fn; }, []);

  function disableThreeD(message: string) {
    threeDOffRef.current = true;
    setThreeDOff(true);
    setManifest(null);
    setView('list');
    if (message) setNotice(message);
  }

  async function submit() {
    if (!slot || !staffId || !serviceId) return;
    setError('');
    if (!session) {
      const draft = new URLSearchParams({ staff: staffId, service: serviceId, date, slot, view, step: '2' });
      if (view === '3d' && chairKey) draft.set('chair', chairKey);
      navigate('/customer/login', { state: { from: `/book/${tenantSlug}?${draft}` } });
      return;
    }
    setSubmitting(true);
    try {
      setRequest(await createBookingRequest({ tenantSlug, staffId, serviceId, startsAt: slot,
        ...(note.trim() ? { note: note.trim() } : {}),
        source: view === '3d' && manifest && chairKey ? 'WEB_3D' : 'WEB_LIST',
        ...(view === '3d' && manifest && chairKey ? { sceneVersion: manifest.version, chairKey } : {}),
      }));
    } catch (reason) {
      if (axios.isAxiosError(reason) && reason.response?.data?.code === 'FEATURE_DISABLED') {
        // 3D kill switch sayfa açıkken devreye girdi: seçimler korunur, aynı talep liste kaynağıyla tekrar gönderilebilir.
        disableThreeD('3D görünüm şu anda kapalı. Seçimlerin korundu; “Randevuyu onayla” ile liste görünümünden gönderebilirsin.');
        return;
      }
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
    <h1>{cancelled ? 'Rezervasyonun iptal edildi.' : automatic ? 'Randevun oluşturuldu.' : confirmed ? 'Randevun kesinleşti.' : rejected ? 'Talebin onaylanmadı.' : expired ? 'Onay süresi doldu.' : 'Talebin gönderildi.'}</h1>
    <p>{selectedStaff?.displayName} ile {selectedService?.name}, {new Date(request.startsAt).toLocaleString('tr-TR', { timeZone: shop.timezone })}.</p>
    {automatic && <p className="booking-hold">Doğrulanmış müşteri hesabın sayesinde randevun otomatik onaylandı.</p>}
    {request.status === 'PENDING_APPROVAL' && request.holdExpiresAt && <p className="booking-hold">Talebin berbere iletildi; saat işletme onayına kadar ayrılmış sayılmaz. Kalan değerlendirme süresi <strong>{remaining}</strong>. Bu ekran otomatik güncellenir.</p>}
    {confirmed && request.cancellableUntil && <p className="booking-hold">Çevrim içi iptal sınırı: {new Date(request.cancellableUntil).toLocaleString('tr-TR', { timeZone: shop.timezone })}</p>}
    {request.canCancel && <button className="button button-quiet booking-cancel-button" disabled={cancelling} onClick={() => void cancelCurrent()}>{cancelling ? 'İptal ediliyor...' : 'Rezervasyonu iptal et'}</button>}
    {error && <p role="alert" className="form-error">{error}</p>}
    {(rejected || expired) && <button className="button button-primary" onClick={() => { setRequest(null); setStep(1); }}>Başka saat seç</button>}
    <Link className="text-link" to="/customer/account">Müşteri hesabına git</Link>
    <div className="booking-result-install"><InstallPrompt enabled={features?.pwaInstall !== false} /></div>
  </main>;
  }

  const chairIndex = manifest?.chairs.findIndex(chair => chair.staffId === staffId) ?? -1;
  const chairNumber = chairIndex >= 0 ? String(chairIndex + 1).padStart(2, '0') : '';
  const dayLabel = (value: string) => new Date(`${value}T12:00:00`).toLocaleDateString('tr-TR', { day: 'numeric', month: 'long', year: 'numeric' });
  const shortDay = (value: string) => new Date(`${value}T12:00:00`).toLocaleDateString('tr-TR', { day: 'numeric', month: 'long' });
  const price = (value?: number) => value == null ? '' : `₺${Number(value).toLocaleString('tr-TR')}`;
  const slotLabel = slot ? formatter.format(new Date(slot)) : '';
  const steps = ['Seçim', 'Bilgiler', 'Onay'];

  function next() {
    setError('');
    // Dar ekranda birinci adım ikiye ayrılır: önce berber, sonra saat.
    if (compact && step === 1 && mobileStage === 'barber') { goToTime(); return; }
    if (step === 1) { if (slot) setStep(2); return; }
    if (step === 2) { if (session) setStep(3); else void submit(); return; }
    void submit();
  }

  function goToTime() {
    // Canvas sökülmeden önce son kare görüntüye çevrilir: seçim görünür kalır, WebGL durur.
    setFrozen(capture.current?.() ?? null);
    vitals.current?.stage('time');
    // 3B sahnenin sökülmesi (GPU kaynaklarının bırakılması) pahalıdır. Dokunma yanıt
    // süresi (INP) kısa kalsın diye bu iş, tıklamanın boyanmasından sonraki kareye ertelenir.
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(() => { setMobileStage('time'); setSheet('expanded'); });
    } else {
      setMobileStage('time');
      setSheet('expanded');
    }
  }

  function backToBarber() {
    setFrozen(null);
    setMobileStage('barber');
    setSheet('peek');
    vitals.current?.stage('barber');
  }

  function back() {
    setError('');
    if (step > 1) { setStep(value => (value - 1) as 1 | 2 | 3); return; }
    backToBarber();
  }

  const onBarberStage = compact && step === 1 && mobileStage === 'barber';
  const primaryLabel = onBarberStage ? 'Saat seç'
    : step === 1 ? 'Devam et'
    : step === 2 ? (session ? 'Devam et' : 'Giriş yap ve devam et')
    : submitting ? 'Onaylanıyor...' : 'Randevuyu onayla';
  const primaryDisabled = submitting || !online || (onBarberStage ? !staffId : !slot);


  const sceneNode = view === '3d' && manifest && !featuresReady
    ? <div className="barber-scene"><div className="scene-loading" role="status"><span />Dükkân hazırlanıyor</div></div>
    : view === '3d' && manifest
      ? <Suspense fallback={<div className="barber-scene"><div className="scene-loading" role="status"><span />Dükkân hazırlanıyor</div></div>}>
          <BarberShopScene manifest={manifest} selectedId={staffId} statuses={statuses} compact={compact}
            onSelect={selectStaff} onFailure={() => setView('list')} onReady={sceneReadyHandler} onCaptureReady={captureHandler} />
        </Suspense>
      : <div className="bk-list">
          <div className="bk-list-head"><p>{shop.staff.length} berber</p><h2>Berberini seç</h2><span>{shortDay(date)} için ilk müsait saatler</span></div>
          <div className="bk-list-grid">
            {shop.staff.map(person => {
              const index = manifest?.chairs.findIndex(item => item.staffId === person.id) ?? -1;
              const status = statuses[person.id];
              return <button key={person.id} type="button" aria-pressed={person.id === staffId}
                className={`bk-barber${person.id === staffId ? ' is-selected' : ''}${status?.state === 'full' ? ' is-full' : ''}`}
                onClick={() => selectStaff(person.id, index >= 0 ? manifest?.chairs[index].chairKey : undefined)}>
                <span className="bk-barber-avatar" aria-hidden="true">{initials(person.displayName)}</span>
                <strong>{person.displayName}</strong>
                <small>{index >= 0 ? `Koltuk ${String(index + 1).padStart(2, '0')}` : 'Berber'}</small>
                <em>{status?.state === 'full' ? 'Bugün dolu' : status?.firstSlot ? `İlk müsait ${status.firstSlot}` : 'Saatler yükleniyor'}</em>
              </button>;
            })}
          </div>
        </div>;

  const serviceField = <label className="bk-field">
    <span>Hizmet</span>
    <select value={serviceId} onChange={event => setServiceId(event.target.value)}>
      {shop.services.map(service => <option key={service.id} value={service.id}>{service.name}</option>)}
    </select>
    {selectedService && <small>{selectedService.durationMin} dk · {price(selectedService.price)}</small>}
  </label>;

  const dateField = <label className="bk-field bk-field-date">
    <span>Tarih</span>
    <select value={date} onChange={event => setDate(event.target.value)}>
      {Array.from({ length: 31 }, (_, offset) => dateValue(offset)).map(value => <option key={value} value={value}>{dayLabel(value)}</option>)}
    </select>
  </label>;

  const slotsField = <div className="bk-field">
    <span>Müsait saatler</span>
    <div className="bk-slots" aria-live="polite">
      {slotsLoading ? <p className="bk-muted">Saatler yükleniyor...</p> : availability?.slots.length
        ? availability.slots.map(item => <button type="button" key={item.startsAt} aria-pressed={slot === item.startsAt} className={slot === item.startsAt ? 'is-selected' : ''} onClick={() => setSlot(item.startsAt)}>{formatter.format(new Date(item.startsAt))}</button>)
        : <p className="bk-muted">Bu gün için uygun saat yok. Başka bir berber ya da tarih dene.</p>}
    </div>
  </div>;

  const detailFields = <div className="bk-fields">
    {session ? <div className="bk-info-card">
      <span className="bk-barber-avatar" aria-hidden="true">{initials(session.customer.fullName)}</span>
      <div><strong>{session.customer.fullName}</strong><small>{session.customer.email}</small>{session.customer.phone && <small>{session.customer.phone}</small>}</div>
    </div> : <p className="bk-note">Randevunu kaydedebilmemiz için müşteri hesabınla giriş yapman gerekiyor. Seçimlerin korunur.</p>}
    {session && <label className="bk-field">
      <span>Berbere not <em>(isteğe bağlı)</em></span>
      <textarea rows={3} maxLength={500} value={note} onChange={event => setNote(event.target.value)} placeholder="Örn. yanlar kısa, üst uzun kalsın" />
    </label>}
  </div>;

  const reviewList = <dl className="bk-review">
    <div><dt>Berber</dt><dd>{selectedStaff?.displayName}{chairNumber && ` · Koltuk ${chairNumber}`}</dd></div>
    <div><dt>Hizmet</dt><dd>{selectedService?.name} · {selectedService?.durationMin} dk</dd></div>
    <div><dt>Tarih</dt><dd>{dayLabel(date)} · {slotLabel}</dd></div>
    <div><dt>Ücret</dt><dd>{price(selectedService?.price) || '—'}</dd></div>
    <div><dt>Onay</dt><dd>{session ? 'Güven durumuna göre anında ya da işletme onayıyla' : 'Giriş yaptıktan sonra belirlenir'}</dd></div>
    {note && <div><dt>Not</dt><dd>{note}</dd></div>}
  </dl>;

  const summaryBlock = <div className="bk-summary">
    <div><strong>{selectedService?.name ?? 'Hizmet seç'}</strong><strong>{price(selectedService?.price)}</strong></div>
    <span>{selectedStaff?.displayName.split(' ')[0] ?? 'Berber seç'}</span>
    <span>{slotLabel ? `${shortDay(date)} · ${slotLabel}` : 'Saat seç'}</span>
  </div>;

  const primaryButton = <button type="button" className="bk-primary" disabled={primaryDisabled} onClick={next}>
    {primaryLabel}<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
  </button>;

  const topbar = <header className="bk-topbar">
    <div className="bk-brand">
      <svg viewBox="0 0 32 32" aria-hidden="true"><circle cx="8" cy="24" r="4.5" /><circle cx="24" cy="24" r="4.5" /><path d="M11 21 25 4M21 21 7 4" /></svg>
      <strong>{shop.name.toLocaleUpperCase('tr-TR')}</strong>
      <span>Randevu al</span>
    </div>
    <nav className="bk-view-tabs" aria-label="Berber seçim görünümü">
      <button type="button" className={view === '3d' ? 'is-active' : ''} disabled={!manifest} aria-pressed={view === '3d'}
        title={threeDOff ? '3D görünüm şu anda kapalı' : undefined} onClick={() => setView('3d')}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2.8 8 4.6v9.2l-8 4.6-8-4.6V7.4z M4 7.4l8 4.6 8-4.6 M12 12v9.2" /></svg>3D görünüm
      </button>
      <button type="button" className={view === 'list' ? 'is-active' : ''} aria-pressed={view === 'list'} onClick={() => setView('list')}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6h11M9 12h11M9 18h11M4 6h.01M4 12h.01M4 18h.01" /></svg>Liste
      </button>
    </nav>
    <Link className="bk-account" to={session ? '/customer/account' : '/customer/login'}>{session ? session.customer.fullName : 'Giriş yap'}</Link>
  </header>;

  if (compact) {
    const stageTitle = onBarberStage ? 'Berberini seç' : step === 1 ? 'Saatini seç' : step === 2 ? 'Bilgilerin' : 'Son kontrol';
    const stageSubtitle = onBarberStage
      ? (selectedStaff ? `${selectedStaff.displayName}${chairNumber ? ` · Koltuk ${chairNumber}` : ''}` : 'Koltuğa dokun')
      : `${selectedStaff?.displayName.split(' ')[0] ?? ''}${slotLabel ? ` · ${shortDay(date)} ${slotLabel}` : ''}`;

    return <main className="booking-shell booking-compact" data-stage={onBarberStage ? 'barber' : step === 1 ? 'time' : step === 2 ? 'details' : 'review'}>
      <a className="skip-link" href="#booking-panel">Randevu paneline geç</a>
      {topbar}
      <div className="bk-compact-body">
        {onBarberStage ? <>
          <ServiceChips services={shop.services} value={serviceId} onChange={setServiceId} />
          <section className={`bk-stage bk-stage-${view}`} aria-label="Berber koltuğu seçimi">{sceneNode}</section>
          <BarberStrip staff={shop.staff} chairNumbers={chairNumbers} statuses={statuses} selectedId={staffId}
            modelReady={view !== '3d' || sceneReady} onSelect={id => selectStaff(id)} />
        </> : <div className="bk-frozen">
          {frozen
            ? <img src={frozen} alt="" aria-hidden="true" />
            : <span className="bk-frozen-mark" aria-hidden="true">{chairNumber || initials(selectedStaff?.displayName ?? '')}</span>}
          <div className="bk-frozen-copy">
            <strong>{selectedStaff?.displayName}</strong>
            <small>{chairNumber ? `Koltuk ${chairNumber}` : 'Berber'} · {selectedService?.name}</small>
          </div>
          <button type="button" className="bk-frozen-change" onClick={backToBarber}>Değiştir</button>
        </div>}
      </div>

      <BookingSheet state={sheet} onStateChange={setSheet} labelledBy="booking-panel-title"
        title={<h1 id="booking-panel-title" ref={panelHeading} tabIndex={-1}>{stageTitle}</h1>}
        subtitle={stageSubtitle}
        footer={<div className="bk-sheet-actions" id="booking-panel">
          {summaryBlock}
          <div className="bk-actions">
            {(step > 1 || !onBarberStage) && <button type="button" className="bk-back" onClick={back}>Geri</button>}
            {primaryButton}
          </div>
        </div>}>
        <OfflineBanner />
        {notice && <p className="bk-notice" role="status">{notice}</p>}
        {onBarberStage && <p className="bk-note">Koltuğa ya da alttaki isme dokunarak berberini seç. Seçtiğin hizmete göre ilk müsait saatler gösterilir.</p>}
        {!onBarberStage && step === 1 && <div className="bk-fields">{dateField}{slotsField}</div>}
        {step === 2 && detailFields}
        {step === 3 && reviewList}
        {error && <p role="alert" className="form-error">{error}</p>}
      </BookingSheet>
    </main>;
  }

  return <main className="booking-shell">
    <a className="skip-link" href="#booking-panel">Randevu paneline geç</a>
    {topbar}
    <div className="bk-body">
      <section className={`bk-stage bk-stage-${view}`} aria-label="Berber koltuğu seçimi">{sceneNode}</section>
      <aside className="bk-panel" id="booking-panel" aria-labelledby="booking-panel-title">
        <OfflineBanner />
        {notice && <p className="bk-notice" role="status">{notice}</p>}
        <h1 id="booking-panel-title" ref={panelHeading} tabIndex={-1}>Randevun</h1>
        <p className="bk-sub">{selectedStaff ? selectedStaff.displayName.split(' ')[0] : 'Berber seç'}{chairNumber && ` · Koltuk ${chairNumber}`}</p>
        <ol className="bk-steps">
          {steps.map((label, index) => <li key={label} className={index + 1 === step ? 'is-current' : index + 1 < step ? 'is-done' : ''} aria-current={index + 1 === step ? 'step' : undefined}>
            <span>{index + 1 < step ? '✓' : index + 1}</span>{label}
          </li>)}
        </ol>

        {step === 1 && <div className="bk-fields">{serviceField}{dateField}{slotsField}</div>}
        {step === 2 && detailFields}
        {step === 3 && reviewList}

        {summaryBlock}
        {error && <p role="alert" className="form-error">{error}</p>}
        <div className="bk-actions">
          {step > 1 && <button type="button" className="bk-back" onClick={back}>Geri</button>}
          {primaryButton}
        </div>
      </aside>
    </div>
  </main>;
}
