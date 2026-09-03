import { FormEvent, useState, type ChangeEvent } from 'react';
import { useAuth } from '../store/auth';
import { Button, Card, Input } from '../components/ui';

type Mode = 'login' | 'register';
export function AuthScreen() {
  const { login, register } = useAuth();
  const [mode, setMode] = useState<Mode>('login');
  const [values, setValues] = useState({ email: '', password: '', fullName: '', tenantName: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const update = (key: keyof typeof values) => (event: ChangeEvent<HTMLInputElement>) => setValues({ ...values, [key]: event.target.value });
  async function submit(event: FormEvent) {
    event.preventDefault(); setError('');
    if (!values.email.includes('@') || values.password.length < 6 || (mode === 'register' && (!values.fullName || !values.tenantName))) { setError('Lütfen tüm alanları geçerli doldurun. Şifre en az 6 karakter olmalı.'); return; }
    setBusy(true);
    try { await (mode === 'login' ? login(values) : register(values)); } catch { setError('Bilgiler doğrulanamadı. Lütfen tekrar deneyin.'); } finally { setBusy(false); }
  }
  return <main className="auth-layout"><div className="brand-block"><p className="eyebrow">TENANT OS / 01</p><h1>İşinizi tek bir sakin akışta yönetin.</h1><p className="lede">Ekipler, müşteriler ve operasyonlar için ortak çalışma alanınız.</p></div><Card><div className="auth-heading"><p className="eyebrow">HOŞ GELDİNİZ</p><h2>{mode === 'login' ? 'Hesabınıza giriş yapın' : 'Çalışma alanınızı açın'}</h2></div><form onSubmit={submit}>{mode === 'register' && <><Input label="Ad soyad" value={values.fullName} onChange={update('fullName')} /><Input label="İşletme adı" value={values.tenantName} onChange={update('tenantName')} /></>}<Input label="E-posta" type="email" value={values.email} onChange={update('email')} /><Input label="Şifre" type="password" value={values.password} onChange={update('password')} />{error && <div className="form-error">{error}</div>}<Button disabled={busy}>{busy ? 'Bekleyin...' : mode === 'login' ? 'Giriş yap' : 'Hesap oluştur'}</Button></form><button className="switch" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(''); }}>{mode === 'login' ? 'Yeni bir çalışma alanı oluştur' : 'Zaten hesabınız var mı? Giriş yapın'}</button></Card></main>;
}