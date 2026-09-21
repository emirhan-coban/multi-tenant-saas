import { useEffect, useState, type ChangeEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '../api/client';
import { errorMessage } from '../api/errors';
import { useTenant } from '../store/tenant';
import { Button, Card } from '../components/ui';
import '../operations.css';
import { NotificationPermissionCard } from '../components/NotificationPermissionCard';
import { InstallPrompt } from '../components/InstallPrompt';
import { Modal } from '../components/Modal';

type Counter = { used: number; limit: number | null };
type Usage = { plan: string; staff: Counter; appointments: Counter; resetsAt: string; timezone: string; requestsPerMinute: number };
type Summary = { appointmentCount: number; completedCount: number; revenue: number; unknownPriceCount: number; popularService: string; services: { name: string; count: number }[]; requestCount: number; autoConfirmedCount: number; approvedRequestCount: number; rejectedRequestCount: number; expiredRequestCount: number; cancelledRequestCount: number; noShowCount: number };
type InAppNotification = { id: string; eventType: string; title: string; body: string; relatedType: string | null; relatedId: string | null; readAt: string | null; createdAt: string };
type Utilization = { id: string; name: string; availableMinutes: number; occupiedMinutes: number; percent: number };
type Audit = { id: string; actorName: string; action: string; entityType: string; entityId: string; createdAt: string; details: string };
function useEndpoint<T>(path: string, params?: object, enabled = true) {
  const { tenantId } = useTenant();
  return useQuery({ queryKey: ['tenant', tenantId, 'operations', path, params], queryFn: async ({ signal }) => (await api.get<T>(path, { params, signal })).data, staleTime: 0, enabled });
}
function Failure({ error }: { error: unknown }) { return <p className="form-error" role="alert">{errorMessage(error, 'Veriler yüklenemedi. Sayfayı yeniden açarak tekrar deneyin.')}</p>; }
function CounterBar({ title, value }: { title: string; value: Counter }) {
  return <div className="usage-counter"><div><strong>{title}</strong><span>{value.used} / {value.limit ?? 'Sınırsız'}</span></div>
    {value.limit !== null && <progress aria-label={title} max={value.limit} value={Math.min(value.used, value.limit)} />}
    {value.limit !== null && value.used >= value.limit && <small>Kotanız doldu. <Link to="/app/plan">Plan seçeneklerini inceleyin</Link></small>}
  </div>;
}
export function UsageCard() {
  const query = useEndpoint<Usage>('/api/usage');
  return <Card><p className="eyebrow">PLAN VE KULLANIM</p><h2>Çalışma alanınızın kapasitesi</h2>
    {query.isPending && <p role="status">Kullanım yükleniyor…</p>}{query.isError && <Failure error={query.error} />}
    {query.data && <><p><span className="role-badge">{query.data.plan}</span> · {query.data.requestsPerMinute} istek / dakika</p>
      <CounterBar title="Aktif personel" value={query.data.staff} /><CounterBar title="Bu ay oluşturulan randevular" value={query.data.appointments} />
      <p className="muted">Aylık sıfırlanma: {new Date(query.data.resetsAt).toLocaleString('tr-TR', { timeZone: query.data.timezone })} ({query.data.timezone}). İptal edilen randevular da aylık kullanıma dahildir.</p>
      <Link className="text-link" to="/app/plan">Planı incele →</Link></>}
  </Card>;
}
type Subscription = { planCode: string; planName: string; amountMinor: number; currency: string; status: string; paidFeaturesEnabled: boolean; trialEndsAt: string | null; periodEndsAt: string | null; graceEndsAt: string | null; cancelAtPeriodEnd: boolean };
type Checkout = { checkoutId: string; htmlContent: string; expiresAt: string };
type Invoice = { orderReference: string; invoiceReference: string | null; amountMinor: number; currency: string; status: string; occurredAt: string };
const subscriptionLabels: Record<string,string> = { FREE:'Ücretsiz',TRIALING:'Deneme',ACTIVE:'Aktif',PAST_DUE:'Ödeme bekleniyor',CANCEL_AT_PERIOD_END:'Dönem sonunda iptal',RESTRICTED:'Kısıtlı',CANCELLED:'İptal edildi' };
const emptyBilling = { name:'',surname:'',email:'',gsmNumber:'',identityNumber:'',address:'',city:'',country:'Türkiye',zipCode:'' };
function checkoutDocument(html:string) { return `data:text/html;charset=utf-8,${encodeURIComponent(html)}`; }
export function PlanScreen() {
  const { tenantId, role } = useTenant();
  const client = useQueryClient();
  const subscription = useQuery({ queryKey:['tenant',tenantId,'billing'],queryFn:async()=>(await api.get<Subscription>('/api/billing/subscription')).data,refetchInterval:5000 });
  const invoices = useQuery({ queryKey:['tenant',tenantId,'billing','invoices'],queryFn:async()=>(await api.get<Invoice[]>('/api/billing/invoices')).data });
  const [open,setOpen]=useState(false), [checkout,setCheckout]=useState<Checkout|null>(null), [form,setForm]=useState(emptyBilling);
  const start = useMutation({ mutationFn:async()=>(await api.post<Checkout>('/api/billing/checkout',form)).data,onSuccess:data=>setCheckout(data) });
  const cancel = useMutation({ mutationFn:async()=>api.delete('/api/billing/subscription'),onSuccess:()=>void client.invalidateQueries({queryKey:['tenant',tenantId,'billing']}) });
  const status=subscription.data;
  const price=status?new Intl.NumberFormat('tr-TR',{style:'currency',currency:status.currency}).format(status.amountMinor/100):'';
  const set=(key:keyof typeof form)=>(event:ChangeEvent<HTMLInputElement>)=>setForm(previous=>({...previous,[key]:event.target.value}));
  return <><div className="page-heading"><p className="eyebrow">ABONELİK</p><h1>Plan ve kullanım</h1><p>Ekibiniz büyüdükçe kapasitenizi ve faturalama durumunuzu takip edin.</p></div><UsageCard />
    {subscription.isError&&<Failure error={subscription.error}/>} {subscription.isPending&&<p role="status">Abonelik yükleniyor…</p>}
    <div className="dashboard-grid operations-section"><Card><h2>Ücretsiz</h2><p>2 aktif personel · Ayda 100 randevu · Dakikada 60 istek</p></Card>
      <Card><h2>Profesyonel</h2><p>Sınırsız personel ve aylık randevu · Dakikada 300 istek</p>{status&&<><p><span className="role-badge">{subscriptionLabels[status.status]??status.status}</span> · {price}/ay</p>
        {status.trialEndsAt&&status.status==='TRIALING'&&<p className="muted">Deneme bitişi: {new Date(status.trialEndsAt).toLocaleDateString('tr-TR')}</p>}
        {status.graceEndsAt&&status.status==='PAST_DUE'&&<p className="info-notice">Ödeme alınamadı. {new Date(status.graceEndsAt).toLocaleDateString('tr-TR')} tarihine kadar erişiminiz sürer.</p>}
        {status.periodEndsAt&&status.cancelAtPeriodEnd&&<p className="info-notice">Abonelik {new Date(status.periodEndsAt).toLocaleDateString('tr-TR')} tarihinde sona erecek. Verileriniz korunur.</p>}
        {role==='OWNER'&&!status.paidFeaturesEnabled&&<Button onClick={()=>setOpen(true)}>14 günlük denemeyi başlat</Button>}
        {role==='OWNER'&&status.paidFeaturesEnabled&&!status.cancelAtPeriodEnd&&<Button variant="quiet" disabled={cancel.isPending} onClick={()=>cancel.mutate()}>Dönem sonunda iptal et</Button>}</>}
        {cancel.isError&&<Failure error={cancel.error}/>}</Card></div>
    <section className="operations-section"><h2>Tahsilat ve fatura kayıtları</h2>
      {invoices.isPending&&<p role="status">Tahsilatlar yükleniyor…</p>}{invoices.isError&&<Failure error={invoices.error}/>}
      {invoices.data&&!invoices.data.length&&<p className="muted">Henüz tahsilat kaydı yok.</p>}
      {!!invoices.data?.length&&<div className="operations-table"><table><thead><tr><th>Tarih</th><th>Sipariş</th><th>Tutar</th><th>Durum</th><th>Fatura</th></tr></thead><tbody>{invoices.data.map(invoice=><tr key={invoice.orderReference}><td>{new Date(invoice.occurredAt).toLocaleString('tr-TR')}</td><td>{invoice.orderReference}</td><td>{new Intl.NumberFormat('tr-TR',{style:'currency',currency:invoice.currency}).format(invoice.amountMinor/100)}</td><td>{invoice.status==='PAID'?'Ödendi':'Başarısız'}</td><td>{invoice.invoiceReference??'Hazırlanıyor'}</td></tr>)}</tbody></table></div>}
      <p className="muted">Yasal fatura referansı, günlük mutabakat sırasında sipariş kaydına bağlanır.</p>
    </section>
    {open&&<Modal title="Profesyonel plana geç" busy={start.isPending} onClose={()=>{setOpen(false);setCheckout(null);start.reset();}}>
      {!checkout?<form className="billing-form" onSubmit={event=>{event.preventDefault();start.mutate();}}><p className="muted">Kart bilgilerinizi iyzico'nun güvenli ödeme ekranına gireceksiniz. Aşağıdaki bilgiler faturalama ve ödeme doğrulaması için doğrudan sağlayıcıya iletilir.</p>
        <div className="billing-fields"><label className="field"><span>Ad</span><input required autoComplete="given-name" value={form.name} onChange={set('name')}/></label><label className="field"><span>Soyad</span><input required autoComplete="family-name" value={form.surname} onChange={set('surname')}/></label>
          <label className="field"><span>E-posta</span><input required type="email" autoComplete="email" value={form.email} onChange={set('email')}/></label><label className="field"><span>Telefon (+90…)</span><input required inputMode="tel" autoComplete="tel" value={form.gsmNumber} onChange={set('gsmNumber')}/></label>
          <label className="field"><span>T.C. kimlik no</span><input required inputMode="numeric" autoComplete="off" value={form.identityNumber} onChange={set('identityNumber')}/></label><label className="field"><span>Şehir</span><input required autoComplete="address-level2" value={form.city} onChange={set('city')}/></label>
          <label className="field billing-address"><span>Fatura adresi</span><input required autoComplete="street-address" value={form.address} onChange={set('address')}/></label><label className="field"><span>Ülke</span><input required autoComplete="country-name" value={form.country} onChange={set('country')}/></label><label className="field"><span>Posta kodu</span><input autoComplete="postal-code" value={form.zipCode} onChange={set('zipCode')}/></label></div>
        {start.isError&&<Failure error={start.error}/>}<div className="modal-actions"><Button type="button" variant="quiet" onClick={()=>setOpen(false)}>Vazgeç</Button><Button disabled={start.isPending}>{start.isPending?'Güvenli ekran hazırlanıyor…':'Ödemeye geç'}</Button></div></form>
      :<><p className="muted">Ödeme adımını tamamladıktan sonra bu pencereyi kapatın. Abonelik durumu yalnız sunucu doğrulamasından sonra güncellenir.</p><iframe className="billing-checkout" title="iyzico güvenli ödeme" sandbox="allow-forms allow-scripts allow-top-navigation-by-user-activation" src={checkoutDocument(checkout.htmlContent)}/><div className="modal-actions"><Button onClick={()=>{setOpen(false);setCheckout(null);void subscription.refetch();}}>Durumu kontrol et</Button></div></>}
    </Modal>}</>;
}
function localDate(date: Date) { return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-'); }
export function reportRange(from: string, through: string) {
  const a = new Date(from + 'T00:00:00'), b = new Date(through + 'T00:00:00'); b.setDate(b.getDate() + 1);
  const valid = Number.isFinite(+a) && Number.isFinite(+b) && +b > +a && +b - +a <= 93 * 86400000;
  return { valid, params: valid ? { from: a.toISOString(), to: b.toISOString() } : undefined };
}
function useDates() {
  const today = new Date();
  const [from, setFrom] = useState(localDate(new Date(today.getFullYear(), today.getMonth(), 1)));
  const [to, setTo] = useState(localDate(today));
  return { from, to, setFrom, setTo, ...reportRange(from, to) };
}
function Dates({ dates }: { dates: ReturnType<typeof useDates> }) {
  return <div className="report-filters"><label className="field"><span>Başlangıç tarihi</span><input type="date" value={dates.from} onChange={e => dates.setFrom(e.target.value)} /></label>
    <label className="field"><span>Bitiş tarihi (dahil)</span><input type="date" value={dates.to} onChange={e => dates.setTo(e.target.value)} /></label>
    <p className="muted">Tarayıcı saat dilimi: {Intl.DateTimeFormat().resolvedOptions().timeZone}. En fazla 93 gün.</p>
    {!dates.valid && <p role="alert">Geçerli bir tarih aralığı seçin (en fazla 93 gün).</p>}</div>;
}
function BarChart({ title, rows, maximum, suffix = '' }: { title: string; rows: { name: string; value: number }[]; maximum?: number; suffix?: string }) {
  const max = maximum ?? Math.max(1, ...rows.map(r => r.value));
  return <Card><h2>{title}</h2>{rows.length ? <ul className="report-bars" aria-label={title}>{rows.map((row, i) =>
    <li key={i}><div><span>{row.name}</span><strong>{row.value}{suffix}</strong></div><span className="report-track" aria-hidden="true"><span style={{ width: Math.min(100, row.value / max * 100) + '%' }} /></span></li>)}</ul> : <p>Bu aralıkta veri yok.</p>}</Card>;
}
export function ReportsScreen() {
  const dates = useDates();
  const summary = useEndpoint<Summary>('/api/reports/summary', dates.params, dates.valid);
  const utilization = useEndpoint<Utilization[]>('/api/reports/staff-utilization', dates.params, dates.valid);
  return <><div className="page-heading"><p className="eyebrow">İŞLETME ANALİZİ</p><h1>Raporlar</h1><p>Randevu başlangıç tarihine göre işletmenizin özeti.</p></div><Dates dates={dates} />
    {dates.valid && <>{(summary.isPending || utilization.isPending) && <p role="status">Raporlar hazırlanıyor…</p>}
      {summary.isError && <Failure error={summary.error} />}{utilization.isError && <Failure error={utilization.error} />}
      {summary.data && <><div className="dashboard-grid report-metrics">
        <Card><p>Toplam randevu</p><h2>{summary.data.appointmentCount}</h2><small>İptaller dahil · {summary.data.completedCount} tamamlandı</small></Card>
        <Card><p>Rezervasyon talepleri</p><h2>{summary.data.requestCount}</h2><small>{summary.data.autoConfirmedCount} otomatik · {summary.data.approvedRequestCount} onaylı</small></Card>
        <Card><p>İptal edilen talepler</p><h2>{summary.data.cancelledRequestCount}</h2><small>{summary.data.rejectedRequestCount} reddedildi · {summary.data.expiredRequestCount} süresi doldu</small></Card>
        <Card><p>Gerçekleşen ziyaretler</p><h2>{summary.data.completedCount}</h2><small>{summary.data.noShowCount} gelmedi kaydı</small></Card>
        <Card><p>Tamamlanan randevulardan ciro</p><h2>{Number(summary.data.revenue).toLocaleString('tr-TR', { style: 'currency', currency: 'TRY' })}</h2><small>Randevu anındaki fiyat; ödeme tahsilatı değildir.</small></Card>
        <Card><p>En çok tercih edilen hizmet</p><h2>{summary.data.popularService}</h2><small>İptaller hariç</small></Card></div>
        {summary.data.unknownPriceCount > 0 && <p className="info-notice">{summary.data.unknownPriceCount} eski randevunun geçmiş fiyatı bilinmediği için ciroya dahil edilmedi.</p>}
        <BarChart title="Hizmetlere göre randevular" rows={summary.data.services.map(s => ({ name: s.name, value: Number(s.count) }))} /></>}
      {utilization.data && <div className="operations-section"><BarChart title="Personel doluluk oranları" maximum={100} suffix="%" rows={utilization.data.map(s => ({ name: s.name, value: Number(s.percent) }))} />
        <p className="muted">Çalışma saatlerinden izinler çıkarılır. İptal edilmemiş randevular ve tampon süreleri, kalan çalışma süresiyle kesiştirilir.</p>
        <div className="operations-table"><table><thead><tr><th>Personel</th><th>Çalışılabilir dakika</th><th>Dolu dakika</th><th>Doluluk</th></tr></thead><tbody>{utilization.data.map(s => <tr key={s.id}><td>{s.name}</td><td>{Number(s.availableMinutes)}</td><td>{Number(s.occupiedMinutes)}</td><td>%{s.percent}</td></tr>)}</tbody></table></div></div>}
    </>}</>;
}
const actionLabels: Record<string, string> = { APPOINTMENT_CREATED: 'Randevu oluşturuldu', APPOINTMENT_CANCELLED: 'Randevu iptal edildi', APPOINTMENT_COMPLETED: 'Randevu tamamlandı', APPOINTMENT_NO_SHOW: 'Gelmedi işaretlendi', APPOINTMENT_RESCHEDULED: 'Randevu taşındı', APPOINTMENT_CONFIRMED: 'Randevu güncellendi', STAFF_INVITED: 'Personel davet edildi', STAFF_DEACTIVATED: 'Personel pasifleştirildi', SERVICE_PRICE_CHANGED: 'Hizmet fiyatı değiştirildi', SERVICE_DELETED: 'Hizmet silindi' };
export function AuditScreen() {
  const dates = useDates();
  const [actorId, setActor] = useState(''), [page, setPage] = useState(0);
  const actors = useEndpoint<{ id: string; name: string }[]>('/api/audit-log/actors');
  useEffect(() => { setPage(0); }, [dates.from, dates.to, actorId]);
  const query = useEndpoint<Audit[]>('/api/audit-log', { ...dates.params, actorId: actorId || undefined, page }, dates.valid);
  return <><div className="page-heading"><p className="eyebrow">ŞEFFAFLIK</p><h1>İşlem geçmişi</h1><p>Kimin, ne zaman, hangi değişikliği yaptığını izleyin. Kayıtlar değiştirilemez.</p></div>
    <Dates dates={dates} /><label className="field"><span>İşlemi yapan</span><select value={actorId} onChange={e => setActor(e.target.value)}><option value="">Herkes</option>{actors.data?.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select></label>
    {actors.isError && <Failure error={actors.error} />}
    {dates.valid && <>{query.isPending && <p role="status">Geçmiş yükleniyor…</p>}{query.isError && <Failure error={query.error} />}
      {query.data && <><div className="operations-table"><table><thead><tr><th>Zaman</th><th>Kişi</th><th>İşlem</th><th>Detay</th></tr></thead>
        <tbody>{query.data.map(a => <tr key={a.id}><td>{new Date(a.createdAt).toLocaleString('tr-TR')}</td><td>{a.actorName}</td><td>{actionLabels[a.action] ?? a.action}</td>
          <td><details><summary>Kayıt ayrıntısı</summary><p>{a.entityType} · {a.entityId}</p><pre>{a.details}</pre></details></td></tr>)}</tbody></table></div>
        {!query.data.length && <p>Bu filtrelerle işlem bulunamadı.</p>}
        <div className="report-filters"><Button variant="quiet" disabled={page === 0 || query.isFetching} onClick={() => setPage(p => p - 1)}>Önceki</Button><span>Sayfa {page + 1}</span><Button variant="quiet" disabled={query.data.length < 50 || query.isFetching} onClick={() => setPage(p => p + 1)}>Sonraki</Button></div></>}
    </>}</>;
}
export function NotificationsScreen() {
  const query = useEndpoint<{ remindersEnabled: boolean }>('/api/notification-preferences');
  const inbox = useEndpoint<InAppNotification[]>('/api/notifications/in-app');
  const { tenantId } = useTenant();
  const client = useQueryClient();
  const mutation = useMutation({ mutationFn: async (enabled: boolean) => (await api.patch('/api/notification-preferences', { remindersEnabled: enabled })).data,
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['tenant', tenantId, 'operations', '/api/notification-preferences'] }); } });
  const read = useMutation({mutationFn:async(id:string)=>(await api.post<InAppNotification>(`/api/notifications/in-app/${id}/read`)).data,
    onSuccess:item=>client.setQueryData<InAppNotification[]>(['tenant',tenantId,'operations','/api/notifications/in-app',undefined],rows=>rows?.map(row=>row.id===item.id?item:row))});
  return <><div className="page-heading"><p className="eyebrow">TERCİHLER</p><h1>Bildirimler</h1><p>Bu işletmedeki hesabınıza ait hatırlatma tercihi.</p></div><Card>
    {query.isPending && <p role="status">Tercihler yükleniyor…</p>}{query.isError && <Failure error={query.error} />}
    {query.data && <label className="notification-toggle"><input type="checkbox" checked={query.data.remindersEnabled} disabled={mutation.isPending || query.isFetching} onChange={e => mutation.mutate(e.target.checked)} /><span>Oluşturduğum randevular için hatırlatma gönder</span></label>}
    {mutation.isError && <Failure error={mutation.error} />}{mutation.isSuccess && <p role="status">Tercihiniz kaydedildi.</p>}
    <p className="muted">Bu tercih yalnızca kendi hesabınızın konsol hatırlatmalarını kontrol eder. Müşteri e-postaları randevu formundaki ayrı seçimle açılır. SMS gönderilmez.</p></Card>
    <div className="operations-section staff-push-row"><NotificationPermissionCard audience="staff" /><InstallPrompt context="staff" /></div>
    <section className="operations-section notification-inbox"><div><p className="eyebrow">İŞLEM BİLDİRİMLERİ</p><h2>Gelen kutusu</h2></div>
      {inbox.isPending&&<p role="status">Bildirimler yükleniyor...</p>}{inbox.isError&&<Failure error={inbox.error}/>} {!inbox.isPending&&!inbox.data?.length&&<p className="muted">Henüz işlem bildirimi yok.</p>}
      {inbox.data?.map(item=><button key={item.id} className={item.readAt?'is-read':''} disabled={read.isPending} onClick={()=>{if(!item.readAt)read.mutate(item.id);}}><span><strong>{item.title}</strong><small>{new Date(item.createdAt).toLocaleString('tr-TR')}</small></span><p>{item.body}</p>{!item.readAt&&<i>Yeni</i>}</button>)}
    </section><CustomerEmailStatus /></>;
}
function CustomerEmailStatus() {
  const { role } = useTenant();
  const query = useEndpoint<{ enabled: boolean; configured: boolean; ready: boolean; mode: string }>('/api/notifications/email-status', undefined, role === 'OWNER' || role === 'MANAGER');
  if (role !== 'OWNER' && role !== 'MANAGER') return null;
  return <div className="operations-section"><Card><h2>Müşteriye e-posta gönderimi</h2>
    {query.isPending && <p role="status">Gmail durumu yükleniyor…</p>}{query.isError && <Failure error={query.error} />}
    {query.data && <><p>{query.data.ready ? 'Gmail gönderimi açık.' : 'Gmail gönderimi kapalı veya hesap bilgileri eksik.'}</p>
      <p>{query.data.mode === 'live' ? 'Canlı mod: bildirim seçili randevular müşterinin e-posta adresine gönderilir.' : 'Test modu: yalnızca gönderici Gmail adresine mesaj gönderilir. Diğer alıcılar atlanır.'}</p></>}
    <p className="muted">Randevu formuna müşteri e-posta adresini yazın ve e-posta bildirimini seçin. Onay, iptal, saat değişikliği ve 24 saat önce hatırlatma desteklenir. SMTP kabulü, mesajın gelen kutusuna ulaştığı garantisi değildir.</p>
  </Card></div>;
}
export function OperationalFeedback() {
  const [notice, setNotice] = useState<{ message: string; quota: boolean } | null>(null);
  useEffect(() => {
    const handler = (event: Event) => setNotice((event as CustomEvent).detail);
    window.addEventListener('saas:api-error', handler);
    return () => window.removeEventListener('saas:api-error', handler);
  }, []);
  return notice ? <div className="operations-feedback" role="alert"><p>{notice.message}</p>{notice.quota && <Link to="/app/plan" onClick={() => setNotice(null)}>Planı yükselt — seçenekleri gör</Link>}<Button variant="quiet" onClick={() => setNotice(null)}>Kapat</Button></div> : null;
}
