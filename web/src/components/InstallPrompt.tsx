import { useEffect, useState } from 'react';
import { canPromptInstall, dismissInstall, installDismissed, promptInstall, subscribeInstall } from '../pwa/install';
import { isIos, isStandalone } from '../pwa/push';
import '../pwa.css';

export function InstallPrompt({ enabled = true, context = 'customer' }: { enabled?: boolean; context?: 'customer' | 'staff' }) {
  const [available, setAvailable] = useState(canPromptInstall());
  const [hidden, setHidden] = useState(() => installDismissed());
  useEffect(() => subscribeInstall(() => setAvailable(canPromptInstall())), []);

  if (!enabled || hidden || isStandalone()) return null;
  const ios = isIos();
  if (!available && !ios) return null;

  const close = () => { dismissInstall(); setHidden(true); };
  const title = context === 'staff' ? 'Paneli ana ekrana ekleyin' : 'Randevularını ana ekrana ekle';
  return <aside className="install-prompt" aria-label="Uygulamayı yükle">
    <img src="/icons/icon-192.png" alt="" width={44} height={44} />
    <div>
      <strong>{title}</strong>
      {available
        ? <p>Tek dokunuşla aç, bildirimleri al. Uygulama mağazası gerekmez.</p>
        : <p>Safari'de <strong>Paylaş</strong> düğmesine, ardından <strong>Ana Ekrana Ekle</strong>'ye dokunun.</p>}
    </div>
    {available && <button type="button" className="push-primary" onClick={() => void promptInstall().then(outcome => { if (outcome !== 'accepted') close(); })}>Ekle</button>}
    <button type="button" className="install-close" aria-label="Daveti kapat" onClick={close}>×</button>
  </aside>;
}
