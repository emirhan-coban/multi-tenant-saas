import { useEffect, useRef, useState } from 'react';
import { Link, useBlocker } from 'react-router';
import { Button } from '../components/ui';
import { Modal } from '../components/Modal';
import { useStaff, useWorkingHours, useSaveWorkingHours } from '../query/management';
import { errorMessage } from '../api/errors';
import type { WorkingHours } from '../api/types';
import { dayNames, fromHours, weekErrors, weekPayload, type DayDraft } from '../features/workingHours';

function WeekForm({ personelId, rows, onDirty, onBusy }: { personelId: string; rows: WorkingHours[]; onDirty: (dirty: boolean) => void; onBusy: (busy: boolean) => void }) {
  const [days, setDays] = useState(() => fromHours(rows));
  const [baseline, setBaseline] = useState(() => JSON.stringify(weekPayload(fromHours(rows))));
  const [source, setSource] = useState(0);
  const [errors, setErrors] = useState<Record<number, string>>({});
  const [message, setMessage] = useState('');
  const [failure, setFailure] = useState('');
  const save = useSaveWorkingHours();
  const submitting = useRef(false);
  const dirty = JSON.stringify(weekPayload(days)) !== baseline;
  const blocker = useBlocker(dirty || save.isPending);
  useEffect(() => { onBusy(save.isPending); return () => onBusy(false); }, [save.isPending, onBusy]);
  useEffect(() => { onDirty(dirty || save.isPending); }, [dirty, save.isPending, onDirty]);
  useEffect(() => () => onDirty(false), [onDirty]);
  useEffect(() => {
    if (!dirty) { const next = fromHours(rows); setDays(next); setBaseline(JSON.stringify(weekPayload(next))); }
    // Background refreshes must never overwrite an unsaved draft.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows]);
  useEffect(() => {
    if (!dirty && !save.isPending) return;
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty, save.isPending]);
  function change(index: number, patch: Partial<DayDraft>) { setDays(previous => previous.map((day, i) => i === index ? { ...day, ...patch } : day)); setMessage(''); setFailure(''); setErrors(previous => { const next = { ...previous }; delete next[index]; return next; }); }
  async function submit() {
    if (submitting.current || !dirty) return;
    const validation = weekErrors(days); setErrors(validation); setMessage(''); setFailure('');
    if (Object.keys(validation).length) return;
    submitting.current = true;
    try {
      const saved = await save.mutateAsync({ personelId, days: weekPayload(days) });
      const next = fromHours(saved); setDays(next); setBaseline(JSON.stringify(weekPayload(next))); setMessage('Çalışma saatleri kaydedildi.');
    } catch (error) { setFailure(errorMessage(error, 'Çalışma saatleri kaydedilemedi. Değişiklikleriniz korundu.')); }
    finally { submitting.current = false; }
  }
  return <>
    <div className="hours-shortcut"><label className="field" htmlFor="copy-day"><span id="copy-day-label">Saatleri kopyalanacak gün</span><select aria-labelledby="copy-day-label" id="copy-day" value={source} disabled={save.isPending} onChange={event => setSource(Number(event.target.value))}>{dayNames.map((name, i) => <option key={name} value={i}>{name}</option>)}</select></label><Button variant="quiet" disabled={save.isPending || days[source].closed || !!weekErrors([days[source]])[source]} onClick={() => { setDays(previous => previous.map((day, i) => i < 5 ? { ...previous[source], dayOfWeek: i } : day)); setErrors({}); setMessage('Saatler hafta içine uygulandı. Değişiklikleri kaydetmeyi unutmayın.'); }}>Hafta içi hepsine uygula</Button></div>
    {!rows.length && <p className="info-notice">Henüz çalışma saati tanımlanmadı. Çalışılan günleri açıp saatlerini belirleyin.</p>}
    <form onSubmit={event => { event.preventDefault(); void submit(); }} noValidate>
      <fieldset className="week-grid" disabled={save.isPending}><legend className="sr-only">Haftalık çalışma saatleri</legend>{days.map((day, i) => <div className="day-row" key={i}>
        <label className="day-toggle"><input type="checkbox" checked={!day.closed} aria-label={`${dayNames[i]} açık`} onChange={event => change(i, { closed: !event.target.checked })} /><strong>{dayNames[i]}</strong><span>{day.closed ? 'Kapalı' : 'Açık'}</span></label>
        <div className="day-times"><label className="field"><span>Başlangıç</span><input type="time" step={day.startTime.length > 5 && day.startTime.slice(6) !== "00" ? 1 : 60} aria-label={`${dayNames[i]} başlangıç`} disabled={day.closed} value={day.startTime} aria-invalid={!!errors[i]} aria-describedby={errors[i] ? `day-error-${i}` : undefined} onChange={event => change(i, { startTime: event.target.value })} /></label><span className="time-separator" aria-hidden="true">—</span><label className="field"><span>Bitiş</span><input type="time" step={day.endTime.length > 5 && day.endTime.slice(6) !== "00" ? 1 : 60} aria-label={`${dayNames[i]} bitiş`} disabled={day.closed} value={day.endTime} aria-invalid={!!errors[i]} aria-describedby={errors[i] ? `day-error-${i}` : undefined} onChange={event => change(i, { endTime: event.target.value })} /></label></div>
        {errors[i] && <p id={`day-error-${i}`} className="form-error day-error" role="alert">{errors[i]}</p>}
      </div>)}</fieldset>
      {message && <p className="success-notice" role="status">{message}</p>}{failure && <p className="error-notice" role="alert">{failure}</p>}
      <div className="hours-save"><p className="muted">{dirty ? 'Kaydedilmemiş değişiklikler var.' : 'Değişiklik yok.'}</p><Button disabled={!dirty || save.isPending}>{save.isPending ? 'Kaydediliyor…' : 'Değişiklikleri kaydet'}</Button></div>
    </form>
    {blocker.state === 'blocked' && <Modal title={save.isPending ? 'Kaydetme devam ediyor' : 'Kaydedilmemiş değişiklikler'} onClose={() => blocker.reset()}><p>{save.isPending ? 'Lütfen kaydetme işleminin tamamlanmasını bekleyin.' : 'Bu sayfadan ayrılırsanız kaydetmediğiniz değişiklikler silinecek.'}</p><div className="modal-actions"><Button variant="quiet" data-autofocus="true" onClick={() => blocker.reset()}>Düzenlemeye dön</Button>{!save.isPending && <Button onClick={() => blocker.proceed()}>Kaydetmeden ayrıl</Button>}</div></Modal>}
  </>;
}

function StaffWeek({ id, onDirty, onBusy }: { id: string; onDirty: (dirty: boolean) => void; onBusy: (busy: boolean) => void }) {
  const hours = useWorkingHours(id);
  if (hours.isPending) return <div className="services-skeleton" aria-label="Çalışma saatleri yükleniyor" aria-busy="true">{dayNames.map(name => <div key={name} className="skeleton" aria-hidden="true" />)}</div>;
  const duplicates = hours.data && new Set(hours.data.map(row => row.dayOfWeek)).size !== hours.data.length;
  return <>
    {hours.isError && <div className="error-notice"><p role="alert">{errorMessage(hours.error, 'Çalışma saatleri yüklenemedi. Lütfen tekrar deneyin.')}</p><Button variant="quiet" disabled={hours.isFetching} onClick={() => void hours.refetch()}>Tekrar dene</Button></div>}
    {duplicates ? <p className="error-notice" role="alert">Bu personelde aynı gün için birden fazla saat aralığı bulunuyor. Haftalık ekran gün başına tek aralık desteklediği için mevcut saatler burada düzenlenemiyor.</p> : hours.data && <WeekForm personelId={id} rows={hours.data} onDirty={onDirty} onBusy={onBusy} />}
  </>;
}

export function WorkingHoursScreen() {
  const staff = useStaff();
  const [selected, setSelected] = useState('');
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [next, setNext] = useState<string | null>(null);
  const people = (staff.data ?? []).filter(person => person.active);
  return <>
    <div className="page-heading"><p className="eyebrow">YÖNETİM</p><h1>Çalışma Saatleri</h1><p>Ekibinizin haftalık çalışma düzenini planlayın.</p></div>
    {staff.isPending && <div className="skeleton skeleton-title" aria-label="Personel yükleniyor" aria-busy="true" />}
    {staff.isError && <div className="error-notice"><p role="alert">Personel listesi yüklenemedi.</p><Button variant="quiet" onClick={() => void staff.refetch()} disabled={staff.isFetching}>Tekrar dene</Button></div>}
    {staff.isSuccess && !people.length && <section className="empty-state"><h2>Önce personel ekleyin</h2><p>Çalışma saatlerini tanımlamak için ekibinize bir personel ekleyin.</p><Link className="text-link" to="/app/staff">Personel eklemeye git →</Link></section>}
    {!!people.length && <label className="field staff-picker" htmlFor="hours-person"><span id="hours-person-label">Personel</span><select aria-labelledby="hours-person-label" id="hours-person" disabled={saving} value={selected} onChange={event => { if (dirty) setNext(event.target.value); else setSelected(event.target.value); }}><option value="">Personel seçin</option>{people.map(person => <option key={person.id} value={person.id}>{person.displayName}</option>)}</select></label>}
    {!selected && !!people.length && <p className="info-notice">Haftalık saatlerini düzenlemek istediğiniz personeli seçin.</p>}
    {selected && <StaffWeek key={selected} id={selected} onDirty={setDirty} onBusy={setSaving} />}
    {next !== null && <Modal title="Personeli değiştir" onClose={() => setNext(null)}><p>Personeli değiştirirseniz kaydedilmemiş değişiklikler silinecek. Devam etmek istiyor musunuz?</p><div className="modal-actions"><Button variant="quiet" data-autofocus="true" onClick={() => setNext(null)}>Vazgeç</Button><Button onClick={() => { setSelected(next); setNext(null); setDirty(false); }}>Değişiklikleri bırak</Button></div></Modal>}
  </>;
}
