import { useState, type FormEvent } from 'react';
import { Button } from '../components/ui';
import { Modal } from '../components/Modal';
import { errorMessage } from '../api/errors';
import type { ServiceInput } from '../api/types';
import { useCreateService, useDeleteService, useServices, useUpdateService, type ServiceRow } from '../query/management';

type Values = { name: string; durationMin: string; price: string; bufferMin: string };
const empty: Values = { name: '', durationMin: '30', price: '', bufferMin: '0' };
const money = new Intl.NumberFormat('tr-TR', { style: 'currency', currency: 'TRY' });

function ServiceForm({ initial, busy, error, onSave, onClose }: { initial: Values; busy: boolean; error: string; onSave: (input: ServiceInput, values: Values) => void; onClose: () => void }) {
  const [values, setValues] = useState(initial);
  const [errors, setErrors] = useState<Partial<Values>>({});
  function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const price = Number(values.price.replace(',', '.'));
    const next: Partial<Values> = {};
    if (!values.name.trim()) next.name = 'Hizmet adını girin.';
    if (!Number.isSafeInteger(Number(values.durationMin)) || Number(values.durationMin) < 1 || Number(values.durationMin) > 2147483647) next.durationMin = 'Süreyi pozitif bir tam sayı olarak girin.';
    if (!values.price.trim() || !Number.isFinite(price) || price < 0 || !/^\d+(?:[.,]\d{1,2})?$/.test(values.price)) next.price = 'Fiyatı en fazla iki ondalık basamakla, sıfır veya üzeri girin.';
    if (!values.bufferMin.trim() || !Number.isSafeInteger(Number(values.bufferMin)) || Number(values.bufferMin) < 0 || Number(values.bufferMin) > 2147483647) next.bufferMin = 'Ara süresini sıfır veya pozitif bir tam sayı olarak girin.';
    setErrors(next);
    if (!Object.keys(next).length) onSave({ name: values.name.trim(), durationMin: Number(values.durationMin), price, bufferMin: Number(values.bufferMin) }, values);
  }
  return <form onSubmit={submit} noValidate>
    <p className="muted modal-description">Hizmet bilgilerini girin. Fiyatlar Türk lirası cinsindendir.</p>
    <fieldset disabled={busy} className="service-fields">
      {(['name', 'durationMin', 'price', 'bufferMin'] as const).map(key => <label className="field" key={key} htmlFor={`service-${key}`}>
        <span id={`label-${key}`}>{{ name: 'Hizmet adı', durationMin: 'Süre (dakika)', price: 'Fiyat (₺)', bufferMin: 'Ara süresi (dakika)' }[key]}</span>
        <input aria-labelledby={`label-${key}`} id={`service-${key}`} data-autofocus={key === 'name'} value={values[key]} type={key === 'durationMin' || key === 'bufferMin' ? 'number' : 'text'} inputMode={key === 'price' ? 'decimal' : undefined} min={key === 'durationMin' ? 1 : 0} step="1" required aria-invalid={!!errors[key]} aria-describedby={errors[key] ? `error-${key}` : undefined} onChange={event => setValues(previous => ({ ...previous, [key]: event.target.value }))} />
        {errors[key] && <small id={`error-${key}`}>{errors[key]}</small>}
      </label>)}
    </fieldset>
    {error && <p className="form-error" role="alert">{error}</p>}
    <div className="modal-actions"><Button type="button" variant="quiet" disabled={busy} onClick={onClose}>Vazgeç</Button><Button disabled={busy}>{busy ? 'Kaydediliyor…' : 'Kaydet'}</Button></div>
  </form>;
}

export function ServicesScreen() {
  const services = useServices();
  const create = useCreateService();
  const update = useUpdateService();
  const remove = useDeleteService();
  const [editor, setEditor] = useState<{ id?: string; values: Values } | null>(null);
  const [deleting, setDeleting] = useState<ServiceRow | null>(null);
  const [formError, setFormError] = useState('');
  const [deleteError, setDeleteError] = useState('');
  const [notice, setNotice] = useState('');
  const [failed, setFailed] = useState<{ values: Values; message: string } | null>(null);
  const busy = create.isPending || update.isPending || remove.isPending;
  const rows = services.data ?? [];
  function open(values = empty, id?: string) { setFormError(''); setNotice(''); setEditor({ values, id }); }
  async function save(input: ServiceInput, values: Values) {
    setFormError(''); setNotice('');
    if (editor?.id) {
      try { await update.mutateAsync({ ...input, id: editor.id }); setEditor(null); setNotice('Hizmet güncellendi.'); }
      catch (error) { setFormError(errorMessage(error, 'Hizmet güncellenemedi. Lütfen tekrar deneyin.')); }
    } else {
      setEditor(null); setFailed(null);
      try { await create.mutateAsync(input); setNotice('Hizmet eklendi.'); }
      catch (error) { setFailed({ values, message: errorMessage(error, 'Hizmet eklenemedi. Bilgileriniz korundu; tekrar deneyebilirsiniz.') }); }
    }
  }
  async function confirmDelete() {
    if (!deleting || remove.isPending) return;
    setDeleteError(''); setNotice('');
    try { await remove.mutateAsync(deleting.id); setDeleting(null); setNotice('Hizmet silindi.'); }
    catch (error) { setDeleteError(errorMessage(error, 'Hizmet silinemedi. Lütfen tekrar deneyin.')); }
  }
  return <>
    <div className="page-heading services-heading"><div><p className="eyebrow">YÖNETİM</p><h1>Hizmetler</h1><p className="muted">Sunduğunuz hizmetleri, sürelerini ve fiyatlarını yönetin.</p></div><Button disabled={busy} onClick={() => open()}>+ Hizmet ekle</Button></div>
    {notice && <p className="success-notice" role="status">{notice}</p>}
    {failed && <div className="error-notice"><p role="alert">{failed.message}</p><Button variant="quiet" disabled={busy} onClick={() => { open(failed.values); setFailed(null); }}>Bilgileri aç ve tekrar dene</Button></div>}
    {services.isError && <div className="error-notice"><p role="alert">{errorMessage(services.error, 'Hizmetler yüklenemedi. Lütfen tekrar deneyin.')}{services.data ? ' Son alınan liste gösteriliyor.' : ''}</p><Button variant="quiet" disabled={services.isFetching} onClick={() => void services.refetch()}>{services.isFetching ? 'Yükleniyor…' : 'Tekrar dene'}</Button></div>}
    {services.isPending && <div className="services-skeleton" aria-busy="true" aria-label="Hizmetler yükleniyor"><span className="sr-only" role="status">Hizmetler yükleniyor…</span>{[1, 2, 3, 4].map(key => <div key={key} className="skeleton" aria-hidden="true" />)}</div>}
    {services.isSuccess && !rows.length && <section className="empty-state"><span className="empty-symbol" aria-hidden="true">＋</span><h2>Henüz hizmet eklemediniz</h2><p>İlk hizmetinizi ekleyerek hizmet listenizi oluşturmaya başlayın.</p><Button disabled={busy} onClick={() => open()}>İlk hizmetini ekle</Button></section>}
    {!!rows.length && <section className="services-list" aria-label="Hizmet listesi"><div className="service-table-head" aria-hidden="true"><span>Hizmet</span><span>Süre</span><span>Ara süresi</span><span>Fiyat</span><span>İşlemler</span></div><ul>{rows.map(row => <li className={`service-row${row.optimistic ? ' is-pending' : ''}`} key={row.id}>
      <div className="service-name"><strong>{row.name}</strong>{row.optimistic && <span className="role-badge" role="status">Kaydediliyor…</span>}</div>
      <span><small className="mobile-label">Süre</small>{row.durationMin} dk</span><span><small className="mobile-label">Ara süresi</small>{row.bufferMin} dk</span><span className="service-price"><small className="mobile-label">Fiyat</small>{money.format(row.price)}</span>
      <div className="service-actions"><Button variant="quiet" disabled={busy || row.optimistic} aria-label={`${row.name} hizmetini düzenle`} onClick={() => open({ name: row.name, durationMin: String(row.durationMin), price: String(row.price), bufferMin: String(row.bufferMin) }, row.id)}>Düzenle</Button><Button variant="quiet" className="button button-danger-quiet" disabled={busy || row.optimistic} aria-label={`${row.name} hizmetini sil`} onClick={() => { setDeleteError(''); setDeleting(row); }}>Sil</Button></div>
    </li>)}</ul></section>}
    {editor && <Modal title={editor.id ? 'Hizmeti düzenle' : 'Hizmet ekle'} onClose={() => setEditor(null)} busy={update.isPending}><ServiceForm initial={editor.values} busy={update.isPending} error={formError} onSave={save} onClose={() => setEditor(null)} /></Modal>}
    {deleting && <Modal title="Hizmeti sil" onClose={() => setDeleting(null)} busy={remove.isPending}><p><strong>{deleting.name}</strong> hizmetini silmek istediğinizden emin misiniz?</p><p className="muted">Hizmet, listenizden kaldırılacak.</p>{deleteError && <p className="form-error" role="alert">{deleteError}</p>}<div className="modal-actions"><Button variant="quiet" data-autofocus="true" disabled={remove.isPending} onClick={() => setDeleting(null)}>Vazgeç</Button><Button className="button button-danger" disabled={remove.isPending} onClick={confirmDelete}>{remove.isPending ? 'Siliniyor…' : 'Evet, hizmeti sil'}</Button></div></Modal>}
  </>;
}
