import { Link } from 'react-router';
import { UsageCard } from './OperationsScreens';
import { Card } from '../components/ui';
import { useAuth } from '../store/auth';
import { useTenant } from '../store/tenant';
import { canAccess, managementRoles, roleLabels } from '../router/navigation';

export function AppScreen() {
  const { session } = useAuth();
  const { tenantId, role } = useTenant();
  const primary = session?.memberships.find(membership => membership.tenantId === tenantId);
  return <>
    <div className="page-heading"><p className="eyebrow">GENEL BAKIŞ</p><h1>{primary?.tenantName ?? 'Kontrol paneli'}</h1><p>Çalışma alanınıza hoş geldiniz. Günlük işlerinizi buradan yönetin.</p></div>
    <section className="welcome-banner"><div><p className="eyebrow">HER ŞEY BİR ARADA</p><h2>İşinize yer açın.</h2><p>{canAccess(role, managementRoles) ? 'Hizmetleriniz, ekibiniz ve çalışma düzeniniz için ortak bir alan.' : 'Çalışma alanınızı ve hesap bilgilerinizi buradan takip edin.'}</p></div><span className="welcome-art" aria-hidden="true">↗</span></section>
    <div className="dashboard-grid">
      <Card><p className="eyebrow">ÇALIŞMA ALANINDAKİ ROLÜNÜZ</p><h2>{roleLabels[role]}</h2><p className="muted">Menünüz, bu işletmedeki yetkilerinize göre düzenlenir.</p></Card>
      <Card><p className="eyebrow">ÜYELİKLERİNİZ</p><h2>{session?.memberships.length}</h2><p className="muted">Hesabınıza bağlı çalışma alanı</p><Link className="text-link" to="/app/account">Hesap bilgilerini görüntüle <span aria-hidden="true">→</span></Link></Card>
      <Card><p className="eyebrow">MÜŞTERİ REZERVASYONU</p><h2>Bağlantınız hazır</h2><p className="muted">Doğru işletmenin sayfasını QR kodla veya mesajla paylaşın.</p><Link className="text-link" to="/app/booking-link">Paylaşım merkezini aç <span aria-hidden="true">→</span></Link></Card>
    </div>
    {canAccess(role, managementRoles) && <div className="operations-section"><UsageCard /></div>}
  </>;
}
