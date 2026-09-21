# Hukuki değerlendirme paketi

Bu belge ürün davranışını uzman incelemesine hazırlamak için veri akışını ve kayıt türlerini
özetler. Hukuki görüş veya uygunluk onayı değildir.

## Taraflar ve veri akışı

- Platform işletmecisinin unvanı, adresi, VERBİS durumu ve iletişim kanalı canlıya geçmeden doldurulmalıdır.
- İşletme hesabı kendi çalışanlarını, hizmetlerini ve randevularını yönetir.
- Müşteri hesabı birden çok işletmeden randevu alabilir; her işletme yalnızca kendi satırlarını görür.
- PostgreSQL, Redis, barındırma, e-posta, push ve iyzico alt işleyen listesine eklenmelidir.
- Yurt dışı aktarım durumu, seçilen barındırma ve sağlayıcı bölgeleri kesinleşince ayrıca değerlendirilmelidir.

## Ayrı kayıtlar

| Kayıt | Amaç | Zorunluluk | Teknik kanıt |
|---|---|---|---|
| Aydınlatma metni bildirimi | Veri akışı hakkında bilgilendirme | Kayıtta gösterilir | `legal_acceptances / ACKNOWLEDGEMENT` |
| Hizmet kullanım koşulları | Platform sözleşmesi | Hesap açmak için kabul | `legal_acceptances / CONTRACT` |
| Ticari ileti izni | Pazarlama iletisi | İsteğe bağlı, varsayılan kapalı | `purpose_consents / MARKETING` |
| Operasyonel bildirim | Randevu ve hesap güvenliği | Hizmetin yürütülmesi | Bildirim tercihleri ve outbox |

Her belge `legal_documents` tablosunda kod ve sürümle tutulur. Yeni sürüm eski kanıtı
değiştirmez. İzin geri alımları yeni bir `purpose_consents` satırıyla kaydedilir.

## Uzman incelemesi öncesi doldurulacaklar

- Şirket unvanı, MERSİS/vergi bilgileri ve veri sorumlusu iletişim bilgileri
- Kesin saklama süreleri ve hukuki dayanakları
- Alt işleyenlerin isimleri, bölgeleri ve sözleşmeleri
- Mesafeli satış, iptal/iade, fatura ve tüketici başvuru metinleri
- Çerez kullanımı ve analitik araç listesi

## Ödeme veri akışı

- Kart alanları yalnız iyzico hosted checkout içinde doldurulur; uygulama kart numarası veya CVC almaz ve saklamaz.
- Checkout dönüşü ödeme kanıtı sayılmaz. Backend, yüksek entropili token ile sonucu iyzico API'sinden sorgular.
- Yenileme olayları `X-IYZ-SIGNATURE-V3` ile doğrulanır; yalnız sağlayıcı abonelik referansı tenant'a eşlenir.
- Yerelde tutar, para birimi, paket, olay/fatura referansı ve durum saklanır. Kimlik ve fatura adresi checkout başlatılırken sağlayıcıya iletilir, yerel billing tablolarına yazılmaz.
