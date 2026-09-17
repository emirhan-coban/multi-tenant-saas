import { useEffect, useState } from 'react';
import { Button } from '../components/ui';
import { errorMessage } from '../api/errors';
import type { BookingSettings } from '../api/types';
import { useBookingSettings, useUpdateBookingSettings } from '../query/management';

type NumericKey = 'maxFutureAppointments' | 'maxDailyBookings' | 'bookingHorizonDays' | 'minimumLeadMinutes' | 'requestExpiryMinutes' | 'cancellationCutoffMinutes';

export function BookingSettingsScreen() {
  const query = useBookingSettings();
  const update = useUpdateBookingSettings();
  const [form, setForm] = useState<BookingSettings | null>(null);
  useEffect(() => { if (query.data) setForm(query.data); }, [query.data]);
  const number = (key: NumericKey, value: string) => setForm(current => current ? { ...current, [key]: Number(value) } : current);
  async function save() {
    if (!form) return;
    try { setForm(await update.mutateAsync(form)); }
    catch { /* Mutation state renders the actionable error. */ }
  }
  return <section className="booking-settings-page">
    <div className="page-heading"><p className="eyebrow">REZERVASYON POLİTİKASI</p><h1>Rezervasyon ayarları</h1><p>Müşterinin ne zaman ve hangi sınırlar içinde randevu alabileceğini belirleyin.</p></div>
    {query.isPending && <p role="status">Ayarlar yükleniyor...</p>}
    {query.isError && <p role="alert" className="form-error">{errorMessage(query.error, 'Ayarlar yüklenemedi.')}</p>}
    {form && <form className="booking-settings-form" onSubmit={event => { event.preventDefault(); void save(); }}>
      <label className="notification-toggle"><input type="checkbox" checked={form.trustedAutoConfirmEnabled} onChange={event => setForm({ ...form, trustedAutoConfirmEnabled: event.target.checked })} /><span>Güvenilir müşterilerin uygun randevularını otomatik onayla</span></label>
      <div className="settings-grid">
        <label className="field">Müşteri başına aktif randevu<input type="number" min="0" max="20" value={form.maxFutureAppointments} onChange={event => number('maxFutureAppointments', event.target.value)} /></label>
        <label className="field">Günlük rezervasyon sınırı<input type="number" min="1" max="50" value={form.maxDailyBookings} onChange={event => number('maxDailyBookings', event.target.value)} /></label>
        <label className="field">İleri rezervasyon süresi, gün<input type="number" min="1" max="365" value={form.bookingHorizonDays} onChange={event => number('bookingHorizonDays', event.target.value)} /></label>
        <label className="field">En erken rezervasyon, dakika<input type="number" min="0" max="10080" value={form.minimumLeadMinutes} onChange={event => number('minimumLeadMinutes', event.target.value)} /></label>
        <label className="field">Onay talebi süresi, dakika<input type="number" min="5" max="1440" value={form.requestExpiryMinutes} onChange={event => number('requestExpiryMinutes', event.target.value)} /></label>
        <label className="field">İptal ve taşıma sınırı, dakika<input type="number" min="0" max="10080" value={form.cancellationCutoffMinutes} onChange={event => number('cancellationCutoffMinutes', event.target.value)} /></label>
      </div>
      <p className="muted">İptal ve taşıma sınırı randevu başlangıcından geriye doğru hesaplanır. Varsayılan değer 120 dakikadır.</p>
      {update.isError && <p role="alert" className="form-error">{errorMessage(update.error, 'Ayarlar kaydedilemedi. Sayfayı yenileyip tekrar deneyin.')}</p>}
      {update.isSuccess && <p role="status" className="success-notice">Rezervasyon ayarları kaydedildi.</p>}
      <Button type="submit" disabled={update.isPending}>{update.isPending ? 'Kaydediliyor...' : 'Ayarları kaydet'}</Button>
    </form>}
  </section>;
}
