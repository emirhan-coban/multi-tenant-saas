# Satış, faturalama ve destek işletim akışı

## Paket

İlk ücretli sürüm tek paket kullanır: `PRO_MONTHLY`. Depodaki başlangıç değeri aylık
499,00 TRY, 14 gün deneme ve 7 gün ödeme ek süresidir. Canlı tahsilattan önce tutar,
vergi dahil/hariç gösterimi ve deneme süresi şirket yetkilisince onaylanmalı; iyzico
panelindeki plan aynı tutar, para birimi, dönem ve deneme süresiyle oluşturulmalıdır.
Backend farklılığı görürse checkout'u açmaz.

## Tahsilat ve mutabakat

1. İşletme sahibi panelden hosted checkout başlatır; kart verisi uygulamaya girmez.
2. Callback yalnız bir işarettir. Backend token ile iyzico'dan sonucu ve paket referansını sorgular.
3. Yenileme webhook'u V3 imzası, benzersiz olay kimliği ve olay zamanı ile işlenir.
4. Başarılı sipariş `billing_invoices`, başarısız sipariş `PAST_DUE` ve ek süre üretir.
5. Operasyon sorumlusu her iş gününde iyzico siparişlerini `billing_invoices` ile karşılaştırır.
   Eksik/farklı kayıt için destek talebi açılır; tenant hakkı elle, kanıtsız değiştirilmez.
6. Muhasebe sorumlusu sağlayıcı sipariş referansından yasal faturayı düzenler ve fatura
   referansını mutabakat prosedürüyle kayda bağlar. Şirket/fatura entegrasyonu seçilene kadar
   bu adım manueldir.

## İptal ve ödeme gecikmesi

Paneldeki iptal, aboneliği dönem sonunda bitecek şekilde işaretler. O tarihe kadar haklar
sürer; bakım işi dönem sonunda iyzico iptal çağrısını yapar. Ödeme başarısızlığında veri veya
mevcut randevular silinmez. Yedi günlük ek sürede ücretli haklar devam eder, sonrasında hesap
ücretsiz kotaya döner. Destek talebi kategorisi `CANCELLATION` veya `BILLING` seçilir.

## Destek sorumluluğu

`APP_SUPPORT_OWNER` canlı ortamda vardiya/ekip adıyla doldurulur. Kullanıcılar paneldeki
Destek ekranından teknik, ödeme, iptal veya gizlilik talebi açar; OWNER/MANAGER son 100 talebi
görür. Pilot sorumlusu açık talepleri her iş günü kontrol eder, durumu ve çözüm notunu harici
operasyon günlüğünde tutar. Resmî yanıt süresi ancak pilotta ölçüldükten sonra satış metnine yazılır.

## Sağlayıcı kurulumu

- iyzico abonelik ürünü ve V3 webhook özelliği hesapta açılır.
- Callback: `https://<alan-adı>/api/public/billing/iyzico/callback`
- Subscription webhook: `https://<alan-adı>/api/public/billing/iyzico/webhook`
- `IYZICO_*` sırları yalnız çalışma ortamında tutulur; repository veya ön yüze verilmez.
- Önce sandbox başarılı ödeme, başarısız yenileme, tekrar webhook, sırasız webhook ve iptal
  kontrolü yapılır. Canlıda düşük riskli kontrollü ilk tahsilat ayrıca kaydedilir.
