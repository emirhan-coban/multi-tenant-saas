import { useEffect, useState } from 'react';
import { applyUpdate, UPDATE_READY_EVENT } from '../pwa/register';
import '../pwa.css';

export function UpdateBanner() {
  const [worker, setWorker] = useState<ServiceWorker | null>(null);
  useEffect(() => {
    const handler = (event: Event) => setWorker((event as CustomEvent<ServiceWorker>).detail);
    window.addEventListener(UPDATE_READY_EVENT, handler);
    return () => window.removeEventListener(UPDATE_READY_EVENT, handler);
  }, []);
  if (!worker) return null;
  return <div className="update-banner" role="status">
    <span>Uygulamanın yeni sürümü hazır.</span>
    <button type="button" onClick={() => applyUpdate(worker)}>Yenile</button>
  </div>;
}
