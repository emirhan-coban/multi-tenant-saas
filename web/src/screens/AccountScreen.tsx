import { Card } from '../components/ui';
import { useAuth } from '../store/auth';
import { useTenant } from '../store/tenant';
import { roleLabels } from '../router/navigation';

export function AccountScreen() {
  const { session } = useAuth();
  const { tenantId, role } = useTenant();
  const active = session?.memberships.find(item => item.tenantId === tenantId);
  return <>
    <div className="page-heading"><p className="eyebrow">HESABIM</p><h1>Hesap bilgileriniz</h1><p>Aktif çalışma alanınızı ve üyeliklerinizi görüntüleyin.</p></div>
    <Card><h2>Aktif çalışma alanı</h2><dl className="account-details"><div><dt>İşletme</dt><dd>{active?.tenantName ?? 'Çalışma alanı'}</dd></div><div><dt>Rolünüz</dt><dd>{roleLabels[role]}</dd></div></dl></Card>
    <section className="memberships-section" aria-labelledby="memberships-title"><h2 id="memberships-title">Üyelikleriniz</h2><ul className="membership-list">{session?.memberships.map(item => <li key={item.tenantId}><span>{item.tenantName}<small>{roleLabels[item.tenantId === tenantId ? role : item.role] ?? 'Tanımsız rol'}</small></span>{item.tenantId === tenantId && <span className="role-badge">Aktif</span>}</li>)}</ul></section>
  </>;
}
