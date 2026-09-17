import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { Button, Card, Input } from '../components/ui';
import { errorMessage } from '../api/errors';
import { requestPasswordReset, resetPassword, verifyEmail } from '../api/account';

/**
 * Faz 9 — şifre sıfırlama ve e-posta doğrulama ekranları.
 *
 * Aynı bileşenler hem işletme kullanıcısı hem müşteri için kullanılır; tek fark
 * hangi uca istek gittiği ve başarıdan sonra hangi giriş ekranına dönüldüğüdür.
 */

function useAudience(customer: boolean) {
  return {
    customer,
    loginPath: customer ? '/customer/login' : '/login',
    title: customer ? 'Müşteri hesabı' : 'İşletme hesabı',
  };
}

export function ForgotPasswordScreen({ customer = false }: { customer?: boolean }) {
  const audience = useAudience(customer);
  const [email, setEmail] = useState('');
  const [state, setState] = useState<'idle' | 'sending' | 'sent'>('idle');
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setState('sending');
    setError(null);
    try {
      await requestPasswordReset(email, audience.customer);
      setState('sent');
    } catch (reason) {
      setState('idle');
      setError(errorMessage(reason, 'İstek gönderilemedi. Bağlantınızı kontrol edip tekrar deneyin.'));
    }
  }

  return <main className="app-shell"><Card>
    <p className="eyebrow">{audience.title.toUpperCase()}</p>
    <h1>Şifremi unuttum</h1>
    {state === 'sent'
      // Hesabın var olup olmadığı bilinçli olarak söylenmez.
      ? <>
          <p role="status">Bu adres kayıtlıysa şifre sıfırlama bağlantısını gönderdik. Gelen kutunuzu ve spam klasörünü kontrol edin.</p>
          <p className="muted">Bağlantı bir saat geçerlidir ve yalnızca bir kez kullanılabilir.</p>
          <Link className="text-link" to={audience.loginPath}>Giriş ekranına dön</Link>
        </>
      : <form onSubmit={submit}>
          <p className="muted">Hesabınızın e-posta adresini yazın; sıfırlama bağlantısını gönderelim.</p>
          <Input label="E-posta" type="email" value={email} required autoComplete="email"
                 onChange={event => setEmail(event.target.value)} />
          {error && <p role="alert" className="form-error">{error}</p>}
          <Button type="submit" disabled={state === 'sending'}>
            {state === 'sending' ? 'Gönderiliyor…' : 'Sıfırlama bağlantısı gönder'}
          </Button>{' '}
          <Link className="text-link" to={audience.loginPath}>Vazgeç</Link>
        </form>}
  </Card></main>;
}

export function ResetPasswordScreen({ customer = false }: { customer?: boolean }) {
  const audience = useAudience(customer);
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [repeat, setRepeat] = useState('');
  const [state, setState] = useState<'idle' | 'saving' | 'done'>('idle');
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (password !== repeat) { setError('İki şifre aynı değil.'); return; }
    setState('saving');
    setError(null);
    try {
      await resetPassword(token, password, audience.customer);
      setState('done');
    } catch (reason) {
      setState('idle');
      setError(errorMessage(reason, 'Şifre değiştirilemedi. Bağlantı geçersiz veya süresi dolmuş olabilir.'));
    }
  }

  if (!token) return <main className="app-shell"><Card>
    <h1>Bağlantı eksik</h1>
    <p role="alert">Bu sayfaya e-postadaki bağlantıyla gelmelisiniz.</p>
    <Link className="text-link" to={customer ? '/musteri/sifremi-unuttum' : '/sifremi-unuttum'}>Yeni bağlantı iste</Link>
  </Card></main>;

  return <main className="app-shell"><Card>
    <p className="eyebrow">{audience.title.toUpperCase()}</p>
    <h1>Yeni şifre belirle</h1>
    {state === 'done'
      ? <>
          <p role="status">Şifreniz değiştirildi. Güvenlik için tüm cihazlardaki oturumlar kapatıldı.</p>
          <Button onClick={() => navigate(audience.loginPath)}>Giriş yap</Button>
        </>
      : <form onSubmit={submit}>
          <p className="muted">En az 8 karakter; harf ve rakam/işaret birlikte olmalı.</p>
          <Input label="Yeni şifre" type="password" value={password} required minLength={8} autoComplete="new-password"
                 onChange={event => setPassword(event.target.value)} />
          <Input label="Yeni şifre (tekrar)" type="password" value={repeat} required minLength={8} autoComplete="new-password"
                 onChange={event => setRepeat(event.target.value)} />
          {error && <p role="alert" className="form-error">{error}</p>}
          <Button type="submit" disabled={state === 'saving'}>
            {state === 'saving' ? 'Kaydediliyor…' : 'Şifreyi değiştir'}
          </Button>
        </form>}
  </Card></main>;
}

export function VerifyEmailScreen({ customer = false }: { customer?: boolean }) {
  const audience = useAudience(customer);
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [state, setState] = useState<'working' | 'done' | 'failed'>('working');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) { setState('failed'); setError('Bu sayfaya e-postadaki bağlantıyla gelmelisiniz.'); return; }
    let cancelled = false;
    verifyEmail(token, audience.customer)
      .then(() => { if (!cancelled) setState('done'); })
      .catch(reason => {
        if (cancelled) return;
        setState('failed');
        setError(errorMessage(reason, 'Bağlantı geçersiz veya süresi dolmuş. Hesabınızdan yeni bağlantı isteyebilirsiniz.'));
      });
    return () => { cancelled = true; };
  }, [token, audience.customer]);

  return <main className="app-shell"><Card>
    <p className="eyebrow">{audience.title.toUpperCase()}</p>
    <h1>E-posta doğrulama</h1>
    {state === 'working' && <p role="status">Doğrulanıyor…</p>}
    {state === 'done' && <p role="status">E-posta adresiniz doğrulandı.</p>}
    {state === 'failed' && <p role="alert" className="form-error">{error}</p>}
    <Link className="text-link" to={audience.loginPath}>Giriş ekranına git</Link>
  </Card></main>;
}
