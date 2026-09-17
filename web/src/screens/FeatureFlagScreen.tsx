import { useState } from 'react';
import { Button } from '../components/ui';
import { errorMessage } from '../api/errors';
import { useFeatureFlags, useUpdateFeatureFlag } from '../query/management';
import type { FeatureFlagState } from '../api/management';

/**
 * Faz 9 — özellik anahtarları ekranı (yalnızca işletme sahibi).
 *
 * Global anahtar kapalıysa işletme onu açamaz: acil durumda özelliği kapatan taraf
 * platformdur. Her değişiklik gerekçe ister ve işlem geçmişine yazılır.
 */
const LABELS: Record<string, { title: string; description: string; off: string }> = {
  'booking.3d': {
    title: '3B dükkân görünümü',
    description: 'Müşteri rezervasyon sayfasında koltukları üç boyutlu sahnede seçer.',
    off: 'Kapatıldığında müşteriler aynı randevuyu liste görünümünden alır; hiçbir işlev kaybolmaz.',
  },
  'push.notifications': {
    title: 'Anlık bildirimler',
    description: 'Onay, iptal ve hatırlatmalar tarayıcı bildirimi olarak gönderilir.',
    off: 'Kapatıldığında bildirimler yalnızca uygulama içinde ve e-postayla iletilir.',
  },
  'pwa.install': {
    title: 'Ana ekrana ekleme',
    description: 'Müşteriye uygulamayı telefonuna kurma önerisi gösterilir.',
    off: 'Kapatıldığında kurulum önerisi çıkmaz; site tarayıcıda normal çalışır.',
  },
  'booking.trusted_auto_confirm': {
    title: 'Güvenilir müşteriye otomatik onay',
    description: 'Güven kazanmış müşterilerin uygun randevuları elle onay beklemez.',
    off: 'Kapatıldığında her randevu onay kuyruğuna düşer.',
  },
};

export function FeatureFlagScreen() {
  const query = useFeatureFlags();
  const update = useUpdateFeatureFlag();
  const [pendingKey, setPendingKey] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [target, setTarget] = useState<boolean | null>(null);

  function start(flag: FeatureFlagState, next: boolean | null) {
    setPendingKey(flag.key);
    setTarget(next);
    setReason('');
  }

  async function confirm() {
    if (!pendingKey) return;
    try {
      await update.mutateAsync({ key: pendingKey, enabled: target, reason });
      setPendingKey(null);
    } catch { /* Hata mesajı aşağıda gösteriliyor. */ }
  }

  return <section className="feature-flags-page">
    <div className="page-heading">
      <p className="eyebrow">ÖZELLİK ANAHTARLARI</p>
      <h1>Özellikleri aç / kapat</h1>
      <p>Bir özellik sorun çıkarırsa yayını beklemeden kapatabilirsiniz. Değişiklik anında geçerli olur.</p>
    </div>

    {query.isPending && <p role="status">Anahtarlar yükleniyor…</p>}
    {query.isError && <p role="alert" className="form-error">{errorMessage(query.error, 'Anahtarlar yüklenemedi.')}</p>}

    <ul className="flag-list">
      {(query.data ?? []).map(flag => {
        const copy = LABELS[flag.key] ?? { title: flag.key, description: '', off: '' };
        const lockedByPlatform = !flag.globalEnabled;
        return <li key={flag.key} className="flag-row">
          <div className="flag-row-main">
            <h2>{copy.title}</h2>
            <p className="muted">{copy.description}</p>
            <p className={flag.enabled ? 'flag-state on' : 'flag-state off'}>
              {flag.enabled ? 'Açık' : 'Kapalı'}
              {flag.tenantOverride !== null && <span className="muted"> · işletme ayarı</span>}
              {lockedByPlatform && <span className="muted"> · platform tarafından kapatıldı</span>}
            </p>
            {!flag.enabled && copy.off && <p className="muted">{copy.off}</p>}
          </div>
          <div className="flag-row-actions">
            <Button variant="quiet" disabled={lockedByPlatform || update.isPending}
                    onClick={() => start(flag, !flag.enabled)}>
              {flag.enabled ? 'Kapat' : 'Aç'}
            </Button>
            {flag.tenantOverride !== null && <Button variant="quiet" disabled={update.isPending}
                    onClick={() => start(flag, null)}>Varsayılana dön</Button>}
          </div>

          {pendingKey === flag.key && <form className="flag-reason" onSubmit={event => { event.preventDefault(); void confirm(); }}>
            <label className="field">
              Değişiklik gerekçesi
              <input value={reason} maxLength={250} required autoFocus
                     placeholder="Örn. mobilde sahne donuyor, pilot bitene kadar kapalı"
                     onChange={event => setReason(event.target.value)} />
            </label>
            <p className="muted">Gerekçe işlem geçmişine yazılır; sonradan neden kapatıldığı görülebilir.</p>
            <div className="flag-reason-actions">
              <Button type="submit" disabled={update.isPending || reason.trim().length === 0}>
                {update.isPending ? 'Uygulanıyor…' : target === null ? 'Varsayılana dön' : target ? 'Aç' : 'Kapat'}
              </Button>
              <Button type="button" variant="quiet" onClick={() => setPendingKey(null)}>Vazgeç</Button>
            </div>
          </form>}
        </li>;
      })}
    </ul>

    {update.isError && <p role="alert" className="form-error">{errorMessage(update.error, 'Anahtar değiştirilemedi.')}</p>}
    {update.isSuccess && !pendingKey && <p role="status" className="success-notice">Anahtar güncellendi.</p>}
  </section>;
}
