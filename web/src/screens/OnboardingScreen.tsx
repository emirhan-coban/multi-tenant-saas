import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '../api/client';
import { Button, Card } from '../components/ui';

type Step = { key: string; title: string; path: string; complete: boolean };
type Status = { completed: number; total: number; bookingPath: string; steps: Step[] };

export function OnboardingScreen() {
  const status = useQuery({ queryKey: ['onboarding'], queryFn: async () => (await api.get<Status>('/api/onboarding')).data });
  const data = status.data;
  return <>
    <div className="page-heading"><p className="eyebrow">KOLAY KURULUM</p><h1>İşletmeni yayına hazırla</h1>
      <p>Adımlar otomatik kaydedilir; istediğin zaman kaldığın yerden devam edebilirsin.</p></div>
    {status.isPending && <Card><p>Kurulum durumu yükleniyor…</p></Card>}
    {status.isError && <Card><p role="alert">Kurulum durumu alınamadı.</p><Button onClick={() => void status.refetch()}>Tekrar dene</Button></Card>}
    {data && <Card>
      <div className="onboarding-progress"><strong>{data.completed}/{data.total} adım tamamlandı</strong>
        <progress max={data.total} value={data.completed} /></div>
      <ol className="onboarding-list">{data.steps.map((step, index) => <li key={step.key} className={step.complete ? 'is-complete' : ''}>
        <span aria-hidden="true">{step.complete ? '✓' : index + 1}</span><strong>{step.title}</strong>
        <Link className="text-link" to={step.path}>{step.complete ? 'Gözden geçir' : 'Devam et'}</Link>
      </li>)}</ol>
    </Card>}
  </>;
}
