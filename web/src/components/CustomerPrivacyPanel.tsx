import { useState } from 'react';
import { Link } from 'react-router';
import { Button } from './ui';
import { Modal } from './Modal';
import { errorMessage } from '../api/errors';
import { deleteMyAccount, exportMyData, requestEmailVerification } from '../api/account';
import { useCustomerAuth } from '../store/customer-auth';

const CONFIRMATION = 'HESABIMI SIL';

/**
 * Faz 9 / KVKK — müşteri kendi verisini indirir veya hesabını kapatır.
 *
 * Silme geri alınamaz olduğu için kullanıcı onay metnini elle yazar. Silmeden sonra
 * oturum kapanır; randevu satırları işletmenin muhasebe kaydı olarak kimliksiz kalır.
 */
export function CustomerPrivacyPanel() {
  const { logout } = useCustomerAuth();
  const [busy, setBusy] = useState<'export' | 'verify' | 'delete' | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [confirmation, setConfirmation] = useState('');

  async function download() {
    setBusy('export'); setError(null); setMessage(null);
    try {
      const blob = await exportMyData();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'verilerim.json';
      link.click();
      URL.revokeObjectURL(url);
      setMessage('Verileriniz indirildi.');
    } catch (reason) {
      setError(errorMessage(reason, 'Veriler indirilemedi. Tekrar deneyin.'));
    } finally { setBusy(null); }
  }

  async function verify() {
    setBusy('verify'); setError(null); setMessage(null);
    try {
      await requestEmailVerification(true);
      setMessage('Doğrulama bağlantısı e-posta adresinize gönderildi.');
    } catch (reason) {
      setError(errorMessage(reason, 'Doğrulama bağlantısı gönderilemedi.'));
    } finally { setBusy(null); }
  }

  async function remove() {
    setBusy('delete'); setError(null);
    try {
      await deleteMyAccount(confirmation);
      logout();
    } catch (reason) {
      setError(errorMessage(reason, 'Hesap silinemedi. Tekrar deneyin.'));
      setBusy(null);
    }
  }

  return <section className="privacy-panel">
    <h2>Verilerim ve gizlilik</h2>
    <p className="muted">
      Hangi verilerin neden tutulduğunu <Link className="text-link" to="/kvkk">aydınlatma metninde</Link> bulabilirsiniz.
    </p>
    <div className="privacy-actions">
      <Button variant="quiet" disabled={busy !== null} onClick={() => void download()}>
        {busy === 'export' ? 'Hazırlanıyor…' : 'Verilerimi indir'}
      </Button>
      <Button variant="quiet" disabled={busy !== null} onClick={() => void verify()}>
        {busy === 'verify' ? 'Gönderiliyor…' : 'E-postamı doğrula'}
      </Button>
      <button className="button button-danger" disabled={busy !== null} onClick={() => { setConfirming(true); setConfirmation(''); }}>
        Hesabımı sil
      </button>
    </div>
    {message && <p role="status" className="success-notice">{message}</p>}
    {error && <p role="alert" className="form-error">{error}</p>}

    {confirming && <Modal title="Hesabımı sil" busy={busy === 'delete'} onClose={() => setConfirming(false)}>
      <p className="modal-description">
        Adınız, telefonunuz, e-postanız ve randevu notlarınız kalıcı olarak silinir. Geçmiş randevu
        kayıtları, işletmenin muhasebe kaydı olarak kimliksiz biçimde kalır ve size bağlanamaz.
        Bu işlem geri alınamaz.
      </p>
      <label className="field">
        Onaylamak için “{CONFIRMATION}” yazın
        <input value={confirmation} autoFocus disabled={busy === 'delete'}
               onChange={event => setConfirmation(event.target.value)} />
      </label>
      <div className="modal-actions">
        <Button variant="quiet" disabled={busy === 'delete'} onClick={() => setConfirming(false)}>Vazgeç</Button>
        <button className="button button-danger" disabled={busy === 'delete' || confirmation.trim() !== CONFIRMATION}
                onClick={() => void remove()}>
          {busy === 'delete' ? 'Siliniyor…' : 'Hesabımı kalıcı olarak sil'}
        </button>
      </div>
    </Modal>}
  </section>;
}
