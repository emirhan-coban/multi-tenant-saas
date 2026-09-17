import { useOnlineStatus } from '../pwa/useOnlineStatus';
import '../pwa.css';

export function OfflineBanner({ message = 'İnternet bağlantısı yok. Randevu işlemleri bağlantı gelince yapılabilir.' }: { message?: string }) {
  const online = useOnlineStatus();
  if (online) return null;
  return <div className="offline-banner" role="status"><span aria-hidden="true" />{message}</div>;
}
