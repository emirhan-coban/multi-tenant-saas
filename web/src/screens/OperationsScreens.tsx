import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '../api/client';
import { errorMessage } from '../api/errors';
import { useTenant } from '../store/tenant';
import { Button, Card } from '../components/ui';
import '../operations.css';

type Counter = { used: number; limit: number | null };
type Usage = { plan: string; staff: Counter; appointments: Counter; resetsAt: string; timezone: string; requestsPerMinute: number };
type Summary = { appointmentCount: number; completedCount: number; revenue: number; unknownPriceCount: number; popularService: string; services: { name: string; count: number }[] };
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
export function PlanScreen() {
  const [demo, setDemo] = useState(false);
  return <><div className="page-heading"><p className="eyebrow">ABONELİK</p><h1>Plan ve kullanım</h1><p>Ekibiniz büyüdükçe kapasitenizi takip edin.</p></div><UsageCard />
    <div className="dashboard-grid operations-section"><Card><h2>FREE</h2><p>2 aktif personel · Ayda 100 randevu · Dakikada 60 istek</p></Card>
      <Card><h2>PRO</h2><p>Sınırsız personel ve aylık randevu · Dakikada 300 istek</p><Button onClick={() => setDemo(true)}>Planı yükselt — demo</Button>
        {demo && <p role="status" className="info-notice">Bu bir demo ekranıdır. Ödeme alınmaz ve planınız değişmez. Gerçek abonelik entegrasyonu henüz bulunmuyor.</p>}</Card></div></>;
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
  const { tenantId } = useTenant();
  const client = useQueryClient();
  const mutation = useMutation({ mutationFn: async (enabled: boolean) => (await api.patch('/api/notification-preferences', { remindersEnabled: enabled })).data,
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['tenant', tenantId, 'operations', '/api/notification-preferences'] }); } });
  return <><div className="page-heading"><p className="eyebrow">TERCİHLER</p><h1>Bildirimler</h1><p>Bu işletmedeki hesabınıza ait hatırlatma tercihi.</p></div><Card>
    {query.isPending && <p role="status">Tercihler yükleniyor…</p>}{query.isError && <Failure error={query.error} />}
    {query.data && <label className="notification-toggle"><input type="checkbox" checked={query.data.remindersEnabled} disabled={mutation.isPending || query.isFetching} onChange={e => mutation.mutate(e.target.checked)} /><span>Oluşturduğum randevular için hatırlatma gönder</span></label>}
    {mutation.isError && <Failure error={mutation.error} />}{mutation.isSuccess && <p role="status">Tercihiniz kaydedildi.</p>}
    <p className="muted">Bu tercih yalnızca kendi hesabınızın konsol hatırlatmalarını kontrol eder. Müşteri e-postaları randevu formundaki ayrı seçimle açılır. SMS gönderilmez.</p></Card><CustomerEmailStatus /></>;
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
