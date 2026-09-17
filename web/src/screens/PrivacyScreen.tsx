import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { Card } from '../components/ui';
import { kvkkPolicy } from '../api/account';

/**
 * Faz 9 — KVKK aydınlatma metni.
 *
 * Sürüm numarası sunucudan okunur: kayıt sırasında hangi sürümün onaylandığı
 * veritabanında saklanır, böylece metin güncellendiğinde eski onaylar karışmaz.
 */
export function PrivacyScreen() {
  const [version, setVersion] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    kvkkPolicy(controller.signal).then(data => setVersion(data.version)).catch(() => setVersion(null));
    return () => controller.abort();
  }, []);

  return <main className="app-shell legal-page"><Card>
    <p className="eyebrow">KVKK</p>
    <h1>Kişisel verilerin korunması hakkında aydınlatma metni</h1>
    {version && <p className="muted">Yürürlükteki sürüm: {version}</p>}

    <h2>Hangi verileri işliyoruz?</h2>
    <ul>
      <li><strong>Hesap bilgileri:</strong> ad soyad, e-posta adresi, telefon numarası ve şifrenizin geri döndürülemez özeti.</li>
      <li><strong>Randevu bilgileri:</strong> hangi işletmeden, hangi personelden, hangi hizmeti, ne zaman aldığınız ve randevunun sonucu.</li>
      <li><strong>Teknik kayıtlar:</strong> güvenlik ve kötüye kullanım denetimi için istek zamanı, hata kayıtları ve bildirim aboneliğiniz.</li>
    </ul>

    <h2>Neden işliyoruz?</h2>
    <ul>
      <li>Randevunuzu oluşturmak, onaylamak ve size hatırlatmak (sözleşmenin ifası).</li>
      <li>İşletmenin ticari ve muhasebe kayıtlarını tutması (hukuki yükümlülük).</li>
      <li>Sahte rezervasyon ve kötüye kullanımı önlemek (meşru menfaat).</li>
    </ul>

    <h2>Kimlerle paylaşıyoruz?</h2>
    <p>
      Randevu aldığınız işletme, yalnızca kendi randevularınıza ait bilgileri görür. Başka bir işletme
      verilerinize erişemez; bu ayrım veritabanı düzeyinde satır bazlı güvenlik ile zorunlu kılınmıştır.
      Verileriniz pazarlama amacıyla üçüncü taraflara satılmaz veya aktarılmaz.
    </p>

    <h2>Ne kadar süreyle saklıyoruz?</h2>
    <p>
      Randevu geçmişi, işletmenin yasal saklama süresi boyunca tutulur. Kullanılmış doğrulama
      bağlantıları, okunmuş bildirimler ve teknik kayıtlar otomatik temizlik işiyle düzenli olarak silinir.
    </p>

    <h2>Haklarınız</h2>
    <ul>
      <li><strong>Verilerinizi indirme:</strong> hesabınızdan tek tıkla tüm verinizi JSON dosyası olarak alabilirsiniz.</li>
      <li><strong>Hesabınızı silme:</strong> hesabınızı kapattığınızda ad, telefon, e-posta ve notlarınız kalıcı olarak silinir.
        Randevu satırları, işletmenin muhasebe kaydı olarak kimliksiz biçimde kalır; size bağlanamaz.</li>
      <li><strong>Düzeltme:</strong> hesap bilgilerinizi istediğiniz zaman güncelleyebilirsiniz.</li>
    </ul>
    <p>Bu işlemleri <Link className="text-link" to="/customer/account">hesabım</Link> sayfasından yapabilirsiniz.</p>
  </Card></main>;
}
