import { useState } from 'react';
import { Button, Card } from '../components/ui';
import { logoutEverywhere } from '../api/pwa';
import { errorMessage } from '../api/errors';
import { disablePush } from '../pwa/push';
import { useAuth } from '../store/auth';
import { useTenant } from '../store/tenant';
import { roleLabels } from '../router/navigation';

export function AccountScreen() {
  const { session, logout } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function signOutEverywhere() {
    setBusy(true); setError('');
    await disablePush('staff').catch(() => undefined);
    try { await logoutEverywhere('staff'); logout(); }
    catch (reason) { setError(errorMessage(reason, 'Oturumlar kapatılamadı. Tekrar deneyin.')); setBusy(false); }
  }
  const { tenantId, role } = useTenant();
  const active = session?.memberships.find(item => item.tenantId === tenantId);
  return <>
    <div className="page-heading"><p className="eyebrow">HESABIM</p><h1>Hesap bilgileriniz</h1><p>Aktif çalışma alanınızı ve üyeliklerinizi görüntüleyin.</p></div>
    <Card><h2>Aktif çalışma alanı</h2><dl className="account-details"><div><dt>İşletme</dt><dd>{active?.tenantName ?? 'Çalışma alanı'}</dd></div><div><dt>Rolünüz</dt><dd>{roleLabels[role]}</dd></div></dl></Card>
 <Card><h2>Oturum güvenliği</h2><p>Kaybolan veya paylaşılan bir cihaz varsa tüm cihazlardaki oturumları sonlandırın. Bu cihazda da yeniden giriş yapmanız gerekir; açık kalan ekranlar en geç bir saat içinde kapanır.</p>
      <Button variant="quiet" disabled={busy} onClick={() => void signOutEverywhere()}>{busy ? 'Kapatılıyor…' : 'Tüm cihazlardan çıkış yap'}</Button>
      {error && <p className="form-error" role="alert">{error}</p>}</Card>
    <section className="memberships-section" aria-labelledby="memberships-title"><h2 id="memberships-title">Üyelikleriniz</h2><ul className="membership-list">{session?.memberships.map(item => <li key={item.tenantId}><span>{item.tenantName}<small>{roleLabels[item.tenantId === tenantId ? role : item.role] ?? 'Tanımsız rol'}</small></span>{item.tenantId === tenantId && <span className="role-badge">Aktif</span>}</li>)}</ul></section>
  </>;
}
