import { useQuery } from '@tanstack/react-query';
import { listAppointments } from '../api/appointments';
import { Button, Card } from '../components/ui';

export function MyScheduleScreen() {
  const from = new Date(); from.setHours(0, 0, 0, 0);
  const to = new Date(from); to.setDate(to.getDate() + 7);
  const schedule = useQuery({
    queryKey: ['my-schedule', from.toISOString().slice(0, 10)],
    queryFn: ({ signal }) => listAppointments(from.toISOString(), to.toISOString(), '', signal),
  });
  return <>
    <div className="page-heading"><p className="eyebrow">PERSONEL TAKVİMİ</p><h1>Programım</h1><p>Bugünden başlayarak önündeki yedi gün.</p></div>
    {schedule.isError && <Card><p role="alert">Program yüklenemedi.</p><Button onClick={() => void schedule.refetch()}>Tekrar dene</Button></Card>}
    {schedule.isPending && <Card><p>Program yükleniyor…</p></Card>}
    {schedule.data && !schedule.data.length && <Card><h2>Yaklaşan randevun yok</h2><p className="muted">Yeni randevular burada görünecek.</p></Card>}
    <section className="schedule-list">{schedule.data?.map(item => <Card key={item.id}>
      <p className="eyebrow">{new Date(item.startsAt).toLocaleDateString('tr-TR', { weekday: 'long', day: 'numeric', month: 'long' })}</p>
      <h2>{new Date(item.startsAt).toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' })} · {item.customerName || 'Müşteri'}</h2>
      <p className="muted">Durum: {item.status}</p>
    </Card>)}</section>
  </>;
}
