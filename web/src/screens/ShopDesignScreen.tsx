import { Suspense, lazy, useEffect, useState, type CSSProperties } from 'react';
import { Button } from '../components/ui';
import { PageSkeleton } from '../components/PageSkeleton';
import { errorMessage } from '../api/errors';
import type { ShopScene } from '../api/shop-scene';
import { usePublishShopDesign, useRollbackShopDesign, useSaveShopDesign, useShopDesign } from '../query/management';

const BarberShopScene = lazy(() => import('../components/BarberShopScene').then(module => ({ default: module.BarberShopScene })));
const swatches = ['#245e50', '#7a4034', '#294b70', '#665538'];

export function ShopDesignScreen() {
  const query = useShopDesign();
  const save = useSaveShopDesign();
  const publish = usePublishShopDesign();
  const rollback = useRollbackShopDesign();
  const [draft, setDraft] = useState<ShopScene | null>(null);
  const [selected, setSelected] = useState('');
  const [rollbackVersion, setRollbackVersion] = useState<number | ''>('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');

  useEffect(() => { if (query.data) setDraft(query.data.draft); }, [query.data]);
  if (query.isPending || !draft) return <PageSkeleton />;
  if (query.isError) return <section className="page-stack"><h1>Dükkân tasarımı</h1><p role="alert">Sahne ayarları yüklenemedi.</p></section>;

  const updateChair = (index: number, patch: Partial<ShopScene['chairs'][number]>) => setDraft(current => current ? {
    ...current, chairs: current.chairs.map((chair, chairIndex) => chairIndex === index ? { ...chair, ...patch } : chair),
  } : current);
  const used = new Set(draft.chairs.map(chair => chair.staffId));
  const freeStaff = query.data.staff.filter(member => !used.has(member.id));
  const freeKey = [1, 2, 3].map(n => `chair-${n}`).find(key => !draft.chairs.some(chair => chair.chairKey === key));
  const addChair = () => {
    const member = freeStaff[0];
    if (!freeKey || !member) return;
    const slot = Number(freeKey.slice('chair-'.length));
    setDraft(current => current ? { ...current, chairs: [...current.chairs, {
      chairKey: freeKey, label: member.displayName, staffId: member.id, staffName: member.displayName,
      positionX: (slot - 2) * 3.25, positionZ: -1.25, rotationY: 0,
    }] } : current);
  };
  const removeChair = (index: number) => setDraft(current => current ? { ...current, chairs: current.chairs.filter((_, chairIndex) => chairIndex !== index) } : current);
  const run = async (action: () => Promise<unknown>, success: string) => {
    setError(''); setNotice('');
    try { await action(); setNotice(success); }
    catch (reason) { setError(errorMessage(reason, 'İşlem tamamlanamadı.')); }
  };

  return <section className="shop-design page-stack">
    <header className="page-heading"><div><p className="eyebrow">3D DÜKKÂN</p><h1>Sahne tasarımı</h1><p>Koltukları aktif personele eşleyin, taslağı kontrol edin ve hazır olduğunda yayınlayın.</p></div>
      <div className="scene-version"><span>Yayında</span><strong>v{query.data.published.version}</strong><small>Taslak v{draft.version}</small></div>
    </header>
    <div className="shop-design-layout">
      <div className="shop-design-preview" style={{ '--scene-accent': draft.accentColor } as CSSProperties}>
        <Suspense fallback={<div className="booking-loading">Önizleme hazırlanıyor...</div>}>
          <BarberShopScene manifest={draft} selectedId={selected} onSelect={staffId => setSelected(staffId)} onFailure={() => setError('Bu cihazda 3D önizleme açılamadı. Ayarlarınızı yine de kaydedebilirsiniz.')} />
        </Suspense>
        <span className="draft-badge">TASLAK v{draft.version}</span>
      </div>
      <form className="shop-design-controls" onSubmit={event => { event.preventDefault(); void run(() => save.mutateAsync(draft), 'Taslak kaydedildi.'); }}>
        <fieldset><legend>Şablon</legend><label className="field">Dükkân düzeni<select value={draft.templateKey} onChange={event => setDraft({ ...draft, templateKey: event.target.value as ShopScene['templateKey'] })}><option value="CLASSIC_3_CHAIR">Klasik üç koltuk</option></select></label></fieldset>
        <fieldset><legend>Vurgu rengi</legend><div className="color-swatches">{swatches.map(color => <button key={color} type="button" aria-label={`Renk ${color}`} title={color} className={draft.accentColor === color ? 'is-selected' : ''} style={{ background: color }} onClick={() => setDraft({ ...draft, accentColor: color })} />)}</div></fieldset>
        <fieldset><legend>Koltuk eşlemeleri</legend>{draft.chairs.length === 0 && <p className="field-hint" role="status">Henüz koltuk yok. Personel ekranından bir berber eklediğinizde koltuk otomatik eşlenir; müşteriler o zamana kadar boş koltukları görür.</p>}<div className="chair-editor-list">{draft.chairs.map((chair, index) => <div className="chair-editor-row" key={chair.chairKey}>
          <strong>{index + 1}. koltuk</strong>
          <label>Personel<select value={chair.staffId} onChange={event => { const member = query.data.staff.find(item => item.id === event.target.value); updateChair(index, { staffId: event.target.value, staffName: member?.displayName ?? '' }); }}>{query.data.staff.filter(member => member.id === chair.staffId || !used.has(member.id)).map(member => <option value={member.id} key={member.id}>{member.displayName}</option>)}</select></label>
          <label>Etiket<input value={chair.label} maxLength={60} onChange={event => updateChair(index, { label: event.target.value })} /></label>
          <label>X konumu<input type="number" min="-8" max="8" step="0.25" value={chair.positionX} onChange={event => updateChair(index, { positionX: Number(event.target.value) })} /></label>
          <Button type="button" variant="quiet" onClick={() => removeChair(index)}>Koltuğu kaldır</Button>
        </div>)}</div>
          <div className="chair-editor-actions"><Button type="button" variant="quiet" disabled={!freeKey || !freeStaff.length} onClick={addChair}>+ Koltuk ekle</Button>
            {!query.data.staff.length ? <small className="muted">Önce Personel ekranından berber ekleyin.</small> : !freeStaff.length && draft.chairs.length < 3 ? <small className="muted">Tüm personelin koltuğu var.</small> : draft.chairs.length >= 3 ? <small className="muted">İlk şablonda en fazla üç koltuk kullanılabilir.</small> : null}</div></fieldset>
        {notice && <p className="success-notice" role="status">{notice}</p>}{error && <p className="error-notice" role="alert">{error}</p>}
        <div className="shop-design-actions"><Button disabled={save.isPending}>{save.isPending ? 'Kaydediliyor...' : 'Taslağı kaydet'}</Button><Button type="button" variant="quiet" disabled={save.isPending || publish.isPending || draft.chairs.length === 0} onClick={() => void run(async () => {
          const saved = await save.mutateAsync(draft);
          return publish.mutateAsync(saved.draft.revision);
        }, 'Yeni sahne yayınlandı.')} >{publish.isPending ? 'Yayınlanıyor...' : 'Yayınla'}</Button></div>
        <div className="rollback-control"><label>Önceki sürüme dön<select value={rollbackVersion} onChange={event => setRollbackVersion(event.target.value ? Number(event.target.value) : '')}><option value="">Sürüm seçin</option>{query.data.history.filter(version => version !== query.data.published.version).map(version => <option key={version} value={version}>v{version}</option>)}</select></label><Button type="button" variant="quiet" disabled={!rollbackVersion || rollback.isPending} onClick={() => rollbackVersion && void run(() => rollback.mutateAsync({ targetVersion: rollbackVersion, revision: draft.revision }), `v${rollbackVersion} düzeni yeniden yayınlandı.`)}>Geri al</Button></div>
      </form>
    </div>
  </section>;
}
