import { useState, type ChangeEvent, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Button, Card, Input } from '../components/ui';
import { errorMessage } from '../api/errors';
import { useCustomerAuth } from '../store/customer-auth';

type Mode = 'login' | 'register';

export function CustomerAuthScreen() {
  const { login, register } = useCustomerAuth();
  const [mode, setMode] = useState<Mode>('login');
  const [values, setValues] = useState({ email: '', password: '', fullName: '', phone: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const update = (key: keyof typeof values) => (event: ChangeEvent<HTMLInputElement>) =>
    setValues(current => ({ ...current, [key]: event.target.value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    if (!values.email.includes('@') || values.password.length < 8 || (mode === 'register' && !values.fullName.trim())) {
      setError('E-posta ve ad soyadı kontrol edin. Şifre en az 8 karakter olmalı.');
      return;
    }
    setBusy(true);
    try {
      if (mode === 'login') await login({ email: values.email, password: values.password });
      else await register(values);
    } catch (reason) {
      setError(errorMessage(reason, 'Bilgiler doğrulanamadı. Lütfen tekrar deneyin.'));
    } finally {
      setBusy(false);
    }
  }

  return <main className="customer-auth-layout">
    <section className="customer-auth-intro">
      <p className="eyebrow">MÜŞTERİ RANDEVU ALANI</p>
      <h1>Randevuların tek hesabında.</h1>
      <p>İlk ziyaretin işletme tarafından onaylanır. Tamamlanan ilk ziyaretinden sonra uygun saatleri doğrudan ayırabilirsin.</p>
    </section>
    <Card>
      <div className="auth-heading"><h2>{mode === 'login' ? 'Müşteri girişi' : 'Müşteri hesabı oluştur'}</h2></div>
      <form onSubmit={submit}>
        {mode === 'register' && <>
          <Input label="Ad soyad" autoComplete="name" value={values.fullName} onChange={update('fullName')} />
          <Input label="Telefon (isteğe bağlı)" autoComplete="tel" value={values.phone} onChange={update('phone')} />
        </>}
        <Input label="E-posta" type="email" autoComplete="email" value={values.email} onChange={update('email')} />
        <Input label="Şifre" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={values.password} onChange={update('password')} />
        {error && <div className="form-error" role="alert">{error}</div>}
        <Button disabled={busy}>{busy ? 'Bekleyin...' : mode === 'login' ? 'Giriş yap' : 'Hesap oluştur'}</Button>
      </form>
      {mode === 'login' && <p className="auth-secondary"><Link className="text-link" to="/musteri/sifremi-unuttum">Şifremi unuttum</Link></p>}
      <p className="auth-legal muted">Hesap oluşturarak <Link className="text-link" to="/kvkk">aydınlatma metnini</Link> okuduğunuzu ve kişisel verilerinizin bu kapsamda işlenmesini kabul ettiğinizi beyan edersiniz.</p>
      <button type="button" className="switch" onClick={() => {
        setMode(current => current === 'login' ? 'register' : 'login');
        setError('');
      }}>{mode === 'login' ? 'İlk kez mi geliyorsun? Hesap oluştur' : 'Zaten hesabın var mı? Giriş yap'}</button>
    </Card>
  </main>;
}
