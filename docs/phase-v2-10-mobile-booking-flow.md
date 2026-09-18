# Faz 10 — Telefonda aşamalı rezervasyon akışı

Faz 7.1 sahneyi güzelleştirdi ama telefon düzenini değiştirmedi: sahne 56vh'lik sabit bir
blok, altında hizmet + tarih + saat aynı anda duruyordu. 3B'nin gerçekten değer kattığı tek
karar "hangi berber / hangi koltuk" — mekânsal ve sosyal bir seçim. Tarih ve saat ise veri
işidir; orada sahne yalnızca yer ve pil harcar. Bu faz akışı bu ayrımın üstüne kurar.

## Akış

| Aşama | Ekranda ne var |
|---|---|
| **Berber** | Üstte işletme adı ve hizmet çipleri, ortada sahne, altında berber şeridi, en altta panel "peek" hâlinde |
| **Saat** | Sahne **sökülür**, yerine seçim anında yakalanan donmuş kare gelir; tarih ve saatler ana içerik olur |
| **Bilgiler** | Hesap özeti ve berbere not |
| **Son kontrol** | Berber, hizmet, süre, ücret, onay durumu ve not tek özette |
| **Tamamlama** | Yeni müşteriye "Talebin gönderildi", otomatik onaylanana "Randevun oluşturuldu" |

Masaüstü düzeni (sahne solda, panel sağda) hiç değişmedi; aşamalı akış yalnızca
`max-width: 840px` altında devreye girer. `matchMedia` yoksa masaüstü düzeni kullanılır.

## Tasarım kararları

### Hizmet, sahnenin üstünde

Koltuk etiketlerindeki "ilk müsait 10:00" bilgisi hizmet süresine bağlıdır. Hizmet berberden
sonra seçilseydi, seçim yapıldıktan sonra tüm saatlerin değişmesi gerekirdi — kullanıcının
altından zemin kaymış olurdu. Çipler varsayılan bir hizmetle açılır, saatler baştan doğrudur
ve koltuğa dokunmak doğrudan **tarih-saate** götürür: akıştan bir aşama tamamen kalkar.
Tek hizmetli dükkânda seçilecek bir şey olmadığı için şerit hiç çizilmez.

### İsimler sahneden çıktı

Uzun isimler yan yana koltuklarda birbirini örtüyordu. Etiketi küçültmek kalıcı çözüm değil:
koltuk sayısı arttıkça sorun geri gelir. Sahnede artık yalnızca **koltuk numarası** durur
(48 px'lik yuvarlak hedef); isim, koltuk numarası ve ilk müsait saat, sahnenin altındaki
yatay kaydırılabilir berber şeridindedir. Şerit ile sahne çift yönlü bağlıdır ve şerit,
3B hiç açılmasa bile çalışan gerçek bir seçim aracıdır.

### Sahne küçülmüyor, donuyor

Küçülen bir canvas hâlâ WebGL bağlamı tutar ve her karede GPU harcar. Bunun yerine koltuk
seçildiği anda sahnenin o karesi `toDataURL` ile görüntüye çevrilir ve canvas tamamen
sökülür. Kullanıcı seçtiği koltuğu görmeye devam eder; saat seçerken WebGL hiç çalışmaz.
Görüntü alınamazsa (bağlam kaybı, eski tarayıcı) yerine koltuk numarası rozeti konur.

### Geri tuşu

Her ileri aşama bir history girdisi bırakır; telefon geri tuşu akışta bir aşama geri alır,
dükkândan çıkarmaz. En baştaki aşamada girdi bırakılmadığı için geri tuşu normal davranır.

### Alt panel

Panel sürüklenebilir, ama sürükleme tek yol değildir: başlıkta 48 px'lik açık bir
genişlet/küçült düğmesi vardır (sürükleme yardımcı teknolojilerde ve dokunmatik olmayan
girdilerde güvenilir değil). Panel kipli değildir — küçükken sahne hâlâ kullanılabilir —
bu yüzden odak hapsi yoktur, bölge olarak işaretlenir.

### Klavye

Tek metin alanı "berbere not". `interactive-widget=resizes-content` viewport ayarı içeriği
yukarı iter; desteklemeyen sürümler için `visualViewport` ile ölçülen klavye yüksekliği
`--bk-keyboard` değişkenine yazılır ve panel o kadar yukarı taşınır. İki durumda da sabit
alt çubuk hiçbir alanı örtmez; denetim bunu her aşamada ayrıca sınar.

### Dokunma hedefleri

Akıştaki tüm hedefler **48 CSS piksel** yüksekliğindedir. WCAG 2.2 asgari hedef ölçüsü
istisnalarla 24×24'tür; parmakla kullanılan bu akışta bilinçli olarak belirgin üstünde
kalındı. Denetim her aşamada görünür tüm etkileşimli öğeleri ölçer.

## Performans

* Dar ekranda düşük kalite modeli (`barber-chair-mobile.glb`, 293 KB) ve DPR 1.25 sınırı.
* Yansımalar (`MeshReflectorMaterial`, küp haritası) mobilde tamamen kapalı; temas gölgesi
  tek karede pişirilir; gölge yalnızca yüksek kalitede ve yalnızca seçili koltukta.
* Sahne ekranda değilken ya da sekme arkadayken render döngüsü durur
  (`IntersectionObserver` + `visibilitychange` → `frameloop="never"`).
* Düşük kalitede `powerPreference: 'default'` — pil tüketimi öncelikli.
* Model inerken berber şeridi ve liste çalışır durumdadır; seçim beklemez.
* LCP öğesi canvas değil, anında gelen başlık + çip + şerit bloğudur: LCP hedefi GLB
  boyutundan bağımsız hâle gelir.

## Ölçüm

LCP ve INP tek başına 3B deneyimi anlatmaz: model inerken sayfa "yüklenmiş" görünür ama
kullanıcı henüz seçim yapamaz. Bu yüzden şunlar ayrı toplanır:

| Ölçü | Anlamı | Hedef |
|---|---|---|
| `tts` | Sahnenin **seçilebilir** hâle gelme süresi | ≤ 3,5 s (4G, orta seviye telefon) |
| `list_ready` | Şerit/listeyle seçimin mümkün olduğu an | ≤ 1,5 s |
| `lcp` | Largest Contentful Paint | ≤ 2,5 s |
| `inp` | En kötü etkileşim gecikmesi | ≤ 200 ms |
| `frame_median` | İlk 5 saniyedeki medyan kare süresi | ≤ 20 ms |
| `long_frames` | Aynı pencerede 50 ms'yi aşan kare sayısı | ≤ 5 |

Değerler `POST /api/public/v1/metrics/booking` ucuna `sendBeacon` ile gider. Uç yalnızca
doğrulanmış sayı kabul eder: kimlik, işletme, IP veya serbest metin saklanmaz; bilinmeyen
aşama adları `other` olarak katlanır (etiket kardinalitesi sabit kalır) ve saniyede 20
kayıttan fazlası sessizce atılır. Kayıtlar Faz 8'deki `saas.booking.*` metriklerine eklenir.

## Kamera: kaç koltuk olursa olsun hepsi kadrajda

İlk sürümde dar ekranda **tek koltuk** görünüyordu. Sebep kamera açısı değil,
`OrbitControls`'un `maxDistance={22}` sınırıydı: kamera yeterince geri çekilemiyordu.
Artık gereken mesafe koltukların yatay yayılımından ve ekranın en-boy oranından hesaplanır
(`frameDistance`), yakınlaştırma yalnızca bir çarpandır ve kontrol sınırları bu mesafeye
bağlanır. Sis de mesafeye bağlıdır, yoksa geri çekilen kamerada koltuklar sisde kayboluyordu.

Seçili koltuk artık **renk değiştirmiyor**: yeşil bir dükkânda kırmızıya dönen koltuk
"dolu/engelli" gibi okunuyordu. Seçim; turkuaz kontur, zemin halkası, parlaklık ve
vurgulanmış koltuk numarasıyla anlatılıyor. Dar ekranda "Seçildi" rozeti kaldırıldı
(aynayı örtüyordu ve zaten gereksizdi).

## Doğrulama

| Kontrol | Yöntem | Sonuç |
|---|---|---|
| Tip denetimi | `tsc -b` | Geçti |
| Backend (tümü, kısıtlı DB rolüyle) | `./mvnw test` | **156/156 geçti** |
| Backend ölçüm ucu | `BookingVitalsIntegrationTests` | 4/4 geçti |
| Web birim testleri | `npm test` | **127/127 geçti** (22 dosya) |
| Üretim derlemesi | `npm run build` | Geçti |
| Telefon tarayıcı denetimi | `web/qa/phase10_mobile_audit.py` | **29 kontrol, işlevsel olanların tamamı geçti** |

Denetimin doğruladıkları: aşamalı akış, 3 koltuğun da kadrajda olması, koltuk etiketinde
yalnızca numara, berber şeridinin isimleri taşıması, saat aşamasında canvas sayısının 0'a
düşmesi, donmuş karenin kalması, geri tuşu, panelin düğmeyle açılıp kapanması, üç aşamada da
44 px dokunma hedefleri ve alt çubuğun hiçbir alanı örtmemesi, 320×568 ve 360×420'de yatay
kaydırma olmaması, 3B kapalıyken modelin indirilmemesi ve ölçümde kişisel veri bulunmaması.

### Ölçüm sonuçları ve dürüst sınır

Denetim kapsayıcısında ölçülenler: **LCP ~620 ms**, **TTS ~2,3 sn**, **listReady ~0,65 sn**.
Bunlar bütçelerin içinde.

**INP bu ortamda güvenilir ölçülemez.** Kapsayıcıda WebGL yazılımla taklit edildiği için 3B
sahne açıkken tek bir kare yüzlerce ms sürer; ölçülen değer 1,4–5,7 sn arasında oynuyor ve
telefonu temsil etmiyor. Yine de bu ölçüm gerçek bir hatayı ortaya çıkardı: koltuk seçildiğinde
sahnenin sökülmesi ve tam çözünürlüklü JPEG kodlaması dokunmayı bekletiyordu. İkisi de
düzeltildi (küçültülmüş kare + sökmenin bir sonraki kareye ertelenmesi) ve 3B sahnenin
olmadığı yolda etkileşim **5624 ms → 40 ms**'ye indi. 200 ms hedefi gerçek cihazda ayrıca
ölçülmelidir; saha ölçümü (`/api/public/v1/metrics/booking`) tam da bunun için var.

## Bilinçli olarak yapılmayanlar

* **Masaüstü düzeni değişmedi.** Aşamalı akış geniş ekranda geniş alanı boşa harcardı ve
  mevcut testlerin bir kısmını gereksizce yeniden yazmayı gerektirirdi.
* **"Aynı berber, aynı hizmet" hızlı tekrar kartı** planlandı ama bu faza alınmadı; dönen
  müşteri için en büyük kazanç olacak, ayrı bir adım olarak duruyor.
* **INP'nin tam tanımı** yüzdelik gerektirir; burada en kötü etkileşim gecikmesi ölçülür.
  Bütçeyi aşan bir etkileşim varsa bu değer onu yakalar, ama resmi INP ile birebir değildir.
