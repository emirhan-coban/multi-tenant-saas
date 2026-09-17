import { useEffect, useMemo, useState } from 'react';
import QRCode from 'qrcode';
import { useBookingLink } from '../query/management';
import { Button } from '../components/ui';
import { Modal } from '../components/Modal';

function publicBookingUrl(slug: string) {
  const configured = (import.meta.env.VITE_PUBLIC_APP_URL as string | undefined)?.replace(/\/+$/, '');
  return `${configured || window.location.origin}/book/${encodeURIComponent(slug)}`;
}

export function BookingLinkScreen() {
  const query = useBookingLink();
  const [qrDataUrl, setQrDataUrl] = useState('');
  const [feedback, setFeedback] = useState('');
  const [copyError, setCopyError] = useState('');
  const [posterOpen, setPosterOpen] = useState(false);
  const bookingUrl = useMemo(() => query.data ? publicBookingUrl(query.data.tenantSlug) : '', [query.data]);

  useEffect(() => {
    let active = true;
    if (!bookingUrl) return;
    QRCode.toDataURL(bookingUrl, {
      width: 720, margin: 2, errorCorrectionLevel: 'H',
      color: { dark: '#18251f', light: '#ffffff' },
    }).then(url => { if (active) setQrDataUrl(url); }).catch(() => { if (active) setCopyError('QR kod şu anda oluşturulamadı.'); });
    return () => { active = false; };
  }, [bookingUrl]);

  async function copy(text: string, success: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopyError('');
      setFeedback(success);
    } catch {
      setFeedback('');
      setCopyError('Kopyalama izni verilmedi. Bağlantıyı alandan seçerek kopyalayabilirsiniz.');
    }
  }

  function downloadQr() {
    if (!qrDataUrl || !query.data) return;
    const anchor = document.createElement('a');
    anchor.href = qrDataUrl;
    anchor.download = `${query.data.tenantSlug}-rezervasyon-qr.png`;
    anchor.click();
  }

  if (query.isPending) return <section className="booking-link-loading" aria-label="Rezervasyon bağlantısı yükleniyor"><div className="skeleton skeleton-title" /><div className="skeleton booking-link-skeleton" /></section>;
  if (query.isError || !query.data) return <section><div className="page-heading"><p className="eyebrow">REZERVASYON KANALI</p><h1>Bağlantı yüklenemedi</h1></div><div className="error-notice" role="alert"><p>İşletmenizin rezervasyon bilgilerine ulaşılamadı.</p><Button onClick={() => void query.refetch()} disabled={query.isFetching}>{query.isFetching ? 'Yükleniyor…' : 'Tekrar dene'}</Button></div></section>;

  const message = `${query.data.tenantName} için randevunu buradan seçebilirsin: ${bookingUrl}`;
  const whatsappUrl = `https://wa.me/?text=${encodeURIComponent(message)}`;
  return <section className="booking-link-page">
    <div className="page-heading"><p className="eyebrow">REZERVASYON KANALI</p><h1>Müşterilerin buradan randevu alsın.</h1><p>Bu adres yalnızca {query.data.tenantName} işletmesine aittir. Mesajla paylaşın veya dükkânda QR kodu gösterin.</p></div>
    {feedback && <p className="success-notice" role="status">{feedback}</p>}
    {copyError && <p className="error-notice" role="alert">{copyError}</p>}
    <div className="booking-link-layout">
      <section className="share-workspace" aria-labelledby="share-title">
        <div><p className="eyebrow">TEK VE DOĞRULANMIŞ ADRES</p><h2 id="share-title">Rezervasyon bağlantınız</h2><p className="muted">Müşteri bu adresten işletmenizi, hizmeti ve koltuğu seçer.</p></div>
        <div className="booking-url-row"><label htmlFor="booking-url">Halka açık adres</label><div><input id="booking-url" readOnly value={bookingUrl} onFocus={event => event.currentTarget.select()} /><Button onClick={() => void copy(bookingUrl, 'Bağlantı kopyalandı.')}>Kopyala</Button></div></div>
        <div className="share-actions">
          <a className="button button-primary" href={whatsappUrl} target="_blank" rel="noreferrer">WhatsApp'ta paylaş</a>
          <Button variant="quiet" onClick={() => void copy(message, 'Instagram için mesaj kopyalandı.')}>Instagram için kopyala</Button>
          <a className="button button-quiet" href={bookingUrl} target="_blank" rel="noreferrer">Müşteri sayfasını aç</a>
        </div>
        <dl className="booking-link-facts"><div><dt>İşletme</dt><dd>{query.data.tenantName}</dd></div><div><dt>Saat dilimi</dt><dd>{query.data.timezone}</dd></div><div><dt>Bağlantı anahtarı</dt><dd>{query.data.tenantSlug}</dd></div></dl>
      </section>
      <aside className="qr-tool" aria-labelledby="qr-title">
        <div className="qr-heading"><div><p className="eyebrow">DÜKKÂNDA PAYLAŞ</p><h2 id="qr-title">QR kod</h2></div><span aria-hidden="true">↗</span></div>
        <div className="qr-preview">{qrDataUrl ? <img src={qrDataUrl} alt={`${query.data.tenantName} rezervasyon QR kodu`} /> : <div className="skeleton" aria-label="QR kod hazırlanıyor" />}</div>
        <p>Müşteri telefon kamerasıyla okutup doğrudan sizin rezervasyon ekranınıza gider.</p>
        <div className="qr-actions"><Button onClick={downloadQr} disabled={!qrDataUrl}>QR indir</Button><Button variant="quiet" onClick={() => setPosterOpen(true)} disabled={!qrDataUrl}>Afişi hazırla</Button></div>
      </aside>
    </div>
    {posterOpen && <Modal title="Rezervasyon afişi" onClose={() => setPosterOpen(false)}>
      <div className="booking-poster"><p className="poster-kicker">{query.data.tenantName}</p><h2>Randevunu<br />koltuğunu seçerek al.</h2><img src={qrDataUrl} alt="Yazdırılacak rezervasyon QR kodu" /><p>Kameranı aç, kodu okut ve uygun saatini seç.</p><small>{bookingUrl}</small></div>
      <div className="modal-actions poster-modal-actions"><Button variant="quiet" onClick={() => setPosterOpen(false)}>Kapat</Button><Button onClick={() => window.print()}>Yazdır</Button></div>
    </Modal>}
  </section>;
}
