import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { Button, Card } from '../components/ui';
import { useAuth } from '../store/auth';

export function AppScreen() {
  const { session, logout } = useAuth();
  const [me, setMe] = useState<{ userId: string; tenantId: string; role: string } | null>(null);
  useEffect(() => { api.get('/api/me').then(({ data }) => setMe(data)).catch(() => undefined); }, []);
  const primary = session?.memberships[0];
  return <main className="app-shell"><header><div><p className="eyebrow">ÇALIŞMA ALANI</p><h1>{primary?.tenantName ?? 'Kontrol paneli'}</h1></div><Button variant="quiet" onClick={logout}>Oturumu kapat</Button></header><div className="dashboard-grid"><Card><p className="eyebrow">AKTİF TENANT</p><h2>{primary?.tenantName}</h2><p className="muted">{me ? `${me.role} · ${me.tenantId}` : 'Profil yükleniyor...'}</p></Card><Card><p className="eyebrow">ÜYELİKLER</p><h2>{session?.memberships.length}</h2><p className="muted">Bu hesaba bağlı çalışma alanı</p></Card></div></main>;
}