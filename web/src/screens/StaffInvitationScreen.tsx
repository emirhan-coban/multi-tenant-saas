import { FormEvent, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api } from '../api/client';
import { errorMessage } from '../api/errors';
import { Button, Card, Input } from '../components/ui';

export function StaffInvitationScreen() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError('');
    try {
      await api.post('/api/staff/invitations/accept', { token, password: password || null });
      setDone(true);
    } catch (reason) {
      setError(errorMessage(reason, 'Davet kabul edilemedi. Bağlantı süresi dolmuş olabilir.'));
    } finally { setBusy(false); }
  }

  return <main className="auth-layout"><Card>
    <p className="eyebrow">PERSONEL DAVETİ</p>
    <h1>{done ? 'Davet kabul edildi' : 'Çalışma alanına katılın'}</h1>
    {done ? <><p>Erişiminiz açıldı. Hesabınızla giriş yapabilirsiniz.</p><Link className="text-link" to="/login">Giriş ekranına git</Link></> :
      <form onSubmit={submit}>
        <p className="muted">Yeni bir hesapsanız büyük/küçük harf ve rakam içeren en az 8 karakterli bir şifre belirleyin. Mevcut hesabınız varsa alanı boş bırakabilirsiniz.</p>
        <Input label="Yeni şifre" type="password" autoComplete="new-password" value={password} onChange={event => setPassword(event.target.value)} />
        {error && <p className="form-error" role="alert">{error}</p>}
        <Button disabled={busy || !token}>{busy ? 'Kabul ediliyor…' : 'Daveti kabul et'}</Button>
      </form>}
  </Card></main>;
}
