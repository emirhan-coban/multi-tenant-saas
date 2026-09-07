import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Button } from '../components/ui';
import { Modal } from '../components/Modal';
import { errorMessage } from '../api/errors';
import type { InviteResult } from '../api/types';
import { useInviteStaff, useStaff } from '../query/management';

function InviteResultDialog({ result, onClose }: { result: InviteResult; onClose: () => void }) {
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'error'>('idle');
  const [copying, setCopying] = useState(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  async function copyPassword() {
    if (!result.geciciSifre || copying) return;
    setCopying(true);
    try {
      await navigator.clipboard.writeText(result.geciciSifre);
      if (mounted.current) setCopyState('copied');
    } catch { if (mounted.current) setCopyState('error'); }
    finally { if (mounted.current) setCopying(false); }
  }
  return <Modal title="Personel oluşturuldu" onClose={onClose}>
    <p className="muted">Personel çalışma alanınıza eklendi. Giriş bilgilerini kendisiyle paylaşabilirsiniz.</p>
    <dl className="invite-result-details"><div><dt>E-posta</dt><dd>{result.email}</dd></div></dl>
    {result.geciciSifre ? <div className="password-panel">
      <label className="field" htmlFor="invite-password"><span>Geçici şifre</span></label>
      <div className="password-actions"><input id="invite-password" aria-describedby="password-help" readOnly value={result.geciciSifre} spellCheck={false} autoComplete="off" onFocus={event => event.currentTarget.select()} /><Button variant="quiet" disabled={copying} onClick={copyPassword}>{copying ? 'Kopyalanıyor…' : 'Şifreyi kopyala'}</Button></div>
      <p id="password-help" className="muted">Bu pencere kapandığında geçici şifreyi tekrar görüntüleyemezsiniz.</p>
      {copyState === 'copied' && <p role="status" className="copy-success">Şifre kopyalandı.</p>}
      {copyState === 'error' && <p role="alert" className="form-error">Şifre kopyalanamadı. Şifre alanını seçip elle kopyalayabilirsiniz.</p>}
    </div> : <p className="info-notice">Bu e-posta mevcut bir hesaba ait. Personel, mevcut şifresiyle giriş yapabilir.</p>}
    <p className="muted invite-note">E-posta gönderilmedi. Giriş bilgilerini personelle sizin paylaşmanız gerekiyor.</p>
    <div className="modal-actions"><Button data-autofocus="true" onClick={onClose}>Tamam</Button></div>
  </Modal>;
}

export function StaffScreen() {
  const staff = useStaff();
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState({ displayName: '', email: '' });
  const [errors, setErrors] = useState<{ displayName?: string; email?: string }>({});
  const [error, setError] = useState('');
  const [result, setResult] = useState<InviteResult | null>(null);
  const submitting = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const invite = useInviteStaff(data => {
    if (!mounted.current) return;
    setOpen(false); setValues({ displayName: '', email: '' }); setResult(data);
  });
  function openInvite() {
    setValues({ displayName: '', email: '' }); setErrors({}); setError(''); invite.reset(); setOpen(true);
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    const input = { displayName: values.displayName.trim(), email: values.email.trim() };
    const next: typeof errors = {};
    if (!input.displayName) next.displayName = 'Personelin adını ve soyadını girin.';
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(input.email)) next.email = 'Geçerli bir e-posta adresi girin.';
    setErrors(next); setError('');
    if (Object.keys(next).length) return;
    submitting.current = true;
    try { await invite.mutateAsync(input); }
    catch (failure) { if (mounted.current) setError(errorMessage(failure, 'Personel oluşturulamadı. Bilgileri kontrol edip tekrar deneyin.')); }
    finally { submitting.current = false; }
  }
  const rows = staff.data ?? [];
  return <>
    <div className="page-heading services-heading"><div><p className="eyebrow">YÖNETİM</p><h1>Personel</h1><p className="muted">Ekibinizi görüntüleyin ve yeni çalışma arkadaşlarınızı davet edin.</p></div><Button disabled={invite.isPending} onClick={openInvite}>+ Personel davet et</Button></div>
    {staff.isError && <div className="error-notice"><p role="alert">{errorMessage(staff.error, 'Personel listesi yüklenemedi. Lütfen tekrar deneyin.')}{staff.data ? ' Son alınan liste gösteriliyor.' : ''}</p><Button variant="quiet" disabled={staff.isFetching} onClick={() => void staff.refetch()}>{staff.isFetching ? 'Yükleniyor…' : 'Tekrar dene'}</Button></div>}
    {staff.isPending && <div className="services-skeleton" aria-busy="true" aria-label="Personel yükleniyor"><span className="sr-only" role="status">Personel yükleniyor…</span>{[1, 2, 3].map(key => <div className="skeleton" key={key} aria-hidden="true" />)}</div>}
    {staff.isSuccess && !rows.length && <section className="empty-state"><span className="empty-symbol" aria-hidden="true">＋</span><h2>Henüz personel eklemediniz</h2><p>İlk çalışma arkadaşınızı davet ederek ekibinizi oluşturmaya başlayın.</p><Button disabled={invite.isPending} onClick={openInvite}>İlk personelini davet et</Button></section>}
    {!!rows.length && <section className="staff-list" aria-label="Personel listesi"><div className="staff-list-heading"><h2>Ekibiniz</h2><span className="role-badge">{rows.length} kişi</span></div><ul>{rows.map(person => <li key={person.id}><span className="staff-avatar" aria-hidden="true">{person.displayName.trim().split(/\s+/).slice(0, 2).map(part => part[0]).join('').toLocaleUpperCase('tr-TR')}</span><strong>{person.displayName}</strong><span className={`staff-status${person.active ? '' : ' is-inactive'}`}><span aria-hidden="true" />{person.active ? 'Aktif' : 'Pasif'}</span></li>)}</ul></section>}
    {open && <Modal title="Personel davet et" busy={invite.isPending} onClose={() => setOpen(false)}>
      <form onSubmit={submit} noValidate><p className="modal-description muted">Personel çalışma alanınıza eklenir. Giriş bilgileri işlem sonunda gösterilir; otomatik e-posta gönderilmez.</p>
        <fieldset className="invite-fields" disabled={invite.isPending}>
          <label className="field" htmlFor="invite-name"><span id="invite-name-label">Ad soyad</span><input id="invite-name" aria-labelledby="invite-name-label" data-autofocus="true" autoComplete="name" value={values.displayName} required aria-invalid={!!errors.displayName} aria-describedby={errors.displayName ? 'invite-name-error' : undefined} onChange={event => setValues(previous => ({ ...previous, displayName: event.target.value }))} />{errors.displayName && <small id="invite-name-error">{errors.displayName}</small>}</label>
          <label className="field" htmlFor="invite-email"><span id="invite-email-label">E-posta</span><input id="invite-email" aria-labelledby="invite-email-label" type="email" autoComplete="email" value={values.email} required aria-invalid={!!errors.email} aria-describedby={errors.email ? 'invite-email-error' : undefined} onChange={event => setValues(previous => ({ ...previous, email: event.target.value }))} />{errors.email && <small id="invite-email-error">{errors.email}</small>}</label>
        </fieldset>
        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="modal-actions"><Button type="button" variant="quiet" disabled={invite.isPending} onClick={() => setOpen(false)}>Vazgeç</Button><Button disabled={invite.isPending}>{invite.isPending ? 'Oluşturuluyor…' : 'Personel oluştur'}</Button></div>
      </form>
    </Modal>}
    {result && <InviteResultDialog result={result} onClose={() => { setResult(null); invite.reset(); }} />}
  </>;
}
