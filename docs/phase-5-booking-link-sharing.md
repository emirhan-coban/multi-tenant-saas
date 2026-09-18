# Faz 5 - İşletmeye özel rezervasyon bağlantısı

## Amaç

Her işletme müşterisini doğru rezervasyon ekranına kendi benzersiz bağlantısıyla yönlendirir. Paneldeki paylaşım merkezi bağlantıyı gösterir, WhatsApp mesajı hazırlar, QR kod indirir ve A4 afiş baskısı üretir.

## Akış

1. OWNER, MANAGER veya STAFF panelde `/app/booking-link` sayfasını açar.
2. Web istemcisi `GET /api/booking-link` isteğini personel access token'ıyla gönderir.
3. Backend tenant kimliğini yalnızca doğrulanmış JWT içinden alır. İstek gövdesi, query parametresi veya tarayıcı state'i tenant seçemez.
4. RLS bağlamı transaction içinde ayarlanır ve işletmenin `name`, `slug` ve `timezone` alanları döner.
5. İstemci halka açık adresi `VITE_PUBLIC_APP_URL + /book/{tenantSlug}` olarak kurar. Değişken yoksa mevcut origin kullanılır.
6. Aynı adres kopyalama, WhatsApp mesajı, QR PNG ve yazdırılabilir afişte kullanılır.
7. Müşteri bağlantıyı açınca mevcut halka açık keşif API'si slug ile doğru işletmeyi bulur; hizmet ve berber koltuğu seçimi o tenant içinde kalır.

## API sözleşmesi

`GET /api/booking-link`

Yetki: `OWNER`, `MANAGER`, `STAFF`. Customer audience token kabul edilmez.

```json
{
  "tenantId": "7ce2a364-4a4a-41aa-93ba-e4f2f3d80b87",
  "tenantName": "Moda Berber",
  "tenantSlug": "moda-berber",
  "timezone": "Europe/Istanbul"
}
```

Faz 5 yeni tablo veya migration gerektirmez. `tenants.slug` ilk migration'dan beri benzersizdir; halka açık rezervasyon API'si de bu alanı kullanır.

## Ortam ayarı

Production web build'inde müşteriyle paylaşılacak dış adres açıkça verilmelidir:

```bash
VITE_PUBLIC_APP_URL=https://randevu.ornek.com
```

Sonda `/` olsa da normalize edilir. Yerel geliştirmede değişken verilmezse `http://localhost:5173` kullanılır.

## Güvenlik ve gizlilik

- Slug kullanıcı tarafından düzenlenmez; backend doğrulanmış tenant kaydından okur.
- Endpoint tenant bağlamını transaction içinde ayarlar ve başka tenant kaydı döndüremez.
- QR kod tamamen tarayıcıda üretilir; üçüncü taraf QR servisine URL veya müşteri verisi gönderilmez.
- Paylaşılan sayfa halka açıktır, ancak randevu oluşturma mevcut müşteri oturumu, rate limit, idempotency ve güven/onay kurallarını korur.
- Bağlantı bir sır değildir. Slug değiştirme/rotasyon bu fazın kapsamında değildir.

## Doğrulama

- Backend entegrasyon testi üç personel rolünü, tenant ayrımını, anonim isteği ve customer audience reddini kapsar.
- Frontend testi doğrulanmış URL'yi, QR girdisini, panoya kopyalamayı, WhatsApp bağlantısını ve yazdırma çağrısını kapsar.
- OpenAPI sözleşmesi `BookingLink` şemasını ve yetki yanıtlarını içerir.
