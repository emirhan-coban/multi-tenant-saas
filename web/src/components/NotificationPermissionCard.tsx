import { useCallback, useEffect, useState } from 'react';
import { sendTestPush, type PushAudience } from '../api/pwa';
import { detectPushState, disablePush, enablePush, PushError, syncPush, type PushState } from '../pwa/push';
import { PUSH_CHANGED_EVENT } from '../pwa/register';
import '../pwa.css';

const copy: Record<PushAudience, { what: string; privacy: string }> = {
  customer: {
    what: 'Talebin onaylandığında, reddedildiğinde, saat değiştiğinde veya iptal edildiğinde haber verelim.',
    privacy: 'Bildirimlerde yalnızca işletme, hizmet ve saat yer alır; telefonun ya da notların kilit ekranında görünmez.',
  },
  staff: {
    what: 'Yeni rezervasyon talebi, otomatik onaylanan randevu ve taşıma isteği geldiğinde bu cihaza bildirim gönderelim.',
    privacy: 'Kilit ekranı bildirimlerinde müşteri adı ve telefonu gösterilmez; ayrıntı için uygulamayı açarsınız.',
  },
};

export function NotificationPermissionCard({ audience }: { audience: PushAudience }) {
  const [state, setState] = useState<PushState | 'loading'>('loading');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    const next = await detectPushState();
    setState(next);
    if (next === 'subscribed') await syncPush(audience).catch(() => undefined);
  }, [audience]);

  useEffect(() => {
    void refresh();
    const handler = () => void refresh();
    window.addEventListener(PUSH_CHANGED_EVENT, handler);
    return () => window.removeEventListener(PUSH_CHANGED_EVENT, handler);
  }, [refresh]);

  async function enable() {
    setBusy(true); setError(''); setMessage('');
    try {
      await enablePush(audience);
      setState('subscribed');
      setMessage('Bildirimler bu cihazda açıldı.');
    } catch (reason) {
      const code = reason instanceof PushError ? reason.code : 'server';
      if (code === 'denied') setState('denied');
      else if (code === 'unavailable' || code === 'feature-disabled') setState('unavailable');
      setError(reason instanceof Error ? reason.message : 'Bildirimler açılamadı.');
    } finally { setBusy(false); }
  }

  async function disable() {
    setBusy(true); setError(''); setMessage('');
    try {
      await disablePush(audience);
      setState('prompt');
      setMessage('Bu cihazda bildirimler kapatıldı.');
    } finally { setBusy(false); }
  }

  async function test() {
    setBusy(true); setError(''); setMessage('');
    try {
      const result = await sendTestPush(audience);
      setMessage(result.queued > 0 ? 'Test bildirimi birkaç saniye içinde gelecek.' : 'Test bildirimi az önce gönderildi; bir dakika sonra tekrar deneyin.');
    } catch {
      setError('Test bildirimi gönderilemedi.');
    } finally { setBusy(false); }
  }

  return <section className="push-card" aria-labelledby={`push-title-${audience}`}>
    <div className="push-card-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24"><path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0" /></svg>
    </div>
    <div className="push-card-body">
      <h2 id={`push-title-${audience}`}>Anlık bildirimler</h2>
      <p>{copy[audience].what}</p>
      {state === 'loading' && <p role="status" className="push-muted">Bildirim durumu kontrol ediliyor…</p>}
      {state === 'unsupported' && <p className="push-muted">Bu tarayıcı anlık bildirimleri desteklemiyor. Randevu durumunu bu sayfadan ve e-postadan takip edebilirsiniz.</p>}
      {state === 'needs-install' && <p className="push-muted">iPhone ve iPad'de bildirim almak için önce Safari'de <strong>Paylaş → Ana Ekrana Ekle</strong> ile uygulamayı ekleyin, sonra ana ekrandaki simgeden açın.</p>}
      {state === 'unavailable' && !error && <p className="push-muted">Anlık bildirimler şu anda kapalı.</p>}
      {state === 'denied' && <p className="push-muted">Bildirim izni bu tarayıcıda engellenmiş. Adres çubuğundaki site ayarlarından izin verip sayfayı yenileyin.</p>}
      {(state === 'prompt' || state === 'subscribed') && <p className="push-muted">{copy[audience].privacy}</p>}
      <div className="push-actions">
        {state === 'prompt' && <button type="button" className="push-primary" disabled={busy} onClick={() => void enable()}>
          {busy ? 'Açılıyor…' : 'Bildirimleri aç'}
        </button>}
        {state === 'subscribed' && <>
          <span className="push-on"><span aria-hidden="true" />Bu cihazda açık</span>
          <button type="button" className="push-quiet" disabled={busy} onClick={() => void test()}>Test bildirimi gönder</button>
          <button type="button" className="push-quiet" disabled={busy} onClick={() => void disable()}>Bu cihazda kapat</button>
        </>}
      </div>
      <p className="push-feedback" role="status" aria-live="polite">{message}</p>
      {error && <p className="form-error" role="alert">{error}</p>}
    </div>
  </section>;
}
