import { useState } from 'react';
import { Link } from 'react-router';
import { Button, Card } from '../components/ui';

export function SalesScreen(){return <main className="sales-page"><header className="sales-nav"><strong>Randevu</strong><nav><a href="#faydalar">Faydalar</a><a href="#paket">Paket</a><Link to="/demo">Demoyu dene</Link><Link to="/login">Giriş</Link></nav></header>
  <section className="sales-hero"><p className="eyebrow">BERBERLER İÇİN RANDEVU YÖNETİMİ</p><h1>Telefon trafiğini azaltın, boş koltukları doldurun.</h1><p>Müşteriniz telefondan uygun personeli ve saati seçsin. Siz bekleyen talepleri tek ekrandan onaylayın, programı ve müşteri geçmişini yönetin.</p><div><Link className="button button-primary" to="/demo">Demo rezervasyon yap</Link> <Link className="button button-quiet" to="/login">İşletme paneline gir</Link></div></section>
  <section id="faydalar" className="sales-grid"><Card><h2>Kolay kurulum</h2><p>Hizmet, fiyat, personel ve çalışma saatlerini adım adım tamamlayın.</p></Card><Card><h2>Mobil çalışma</h2><p>Bugünün programını, bekleyen talepleri ve hızlı işlemleri telefondan görün.</p></Card><Card><h2>Kontrollü rezervasyon</h2><p>Çakışma koruması, müşteri güven durumu, e-posta ve anlık bildirimlerle takip edin.</p></Card></section>
  <section id="paket" className="sales-price"><p className="eyebrow">TEK PAKET</p><h2>Profesyonel · 499 TL/ay</h2><p>14 gün deneme · Sınırsız personel ve aylık randevu · Dönem sonunda iptal · 7 gün ödeme ek süresi.</p><p className="muted">Kart ve tahsilat işlemleri iyzico güvenli ödeme ekranında tamamlanır. Fiyat, canlı açılış öncesinde ticari onayla kesinleşir.</p></section>
  <footer><Link to="/kvkk">Gizlilik ve sözleşmeler</Link><span>Destek talepleri işletme panelindeki Destek bölümünden izlenir.</span></footer></main>}

export function DemoBookingScreen(){
  const [step,setStep]=useState(0); const titles=['Hizmetini seç','Berberini seç','Saatini seç','Demo tamamlandı'];
  return <main className="demo-page"><Link to="/tanitim">← Tanıtıma dön</Link><p className="eyebrow">ETKİLEŞİMLİ DEMO</p><h1>{titles[step]}</h1>
    {step===0&&<div className="sales-grid"><button onClick={()=>setStep(1)}><strong>Saç kesimi</strong><span>45 dk · 600 TL</span></button><button onClick={()=>setStep(1)}><strong>Sakal tıraşı</strong><span>30 dk · 350 TL</span></button></div>}
    {step===1&&<div className="sales-grid"><button onClick={()=>setStep(2)}><strong>Emre</strong><span>Bugün müsait</span></button><button onClick={()=>setStep(2)}><strong>Deniz</strong><span>Yarın müsait</span></button></div>}
    {step===2&&<div className="demo-slots">{['10:00','11:20','13:40','16:00'].map(time=><button key={time} onClick={()=>setStep(3)}>{time}</button>)}</div>}
    {step===3&&<Card><h2>Talebiniz hazır</h2><p>Gerçek akışta müşteri giriş yaptıktan sonra talep işletmeye iletilir; onay sonucu e-posta ve bildirimle görünür.</p><Button onClick={()=>setStep(0)}>Yeniden dene</Button></Card>}
    {step>0&&step<3&&<Button variant="quiet" onClick={()=>setStep(value=>value-1)}>Geri</Button>}<p className="muted">Bu tanıtım ödeme veya gerçek rezervasyon oluşturmaz.</p></main>;
}
