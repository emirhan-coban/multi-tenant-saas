# Plan V2 Faz 7 - Surumlu 3D dukkan

Bu faz, 3D koltuk secimini mevcut rezervasyon motorunun alternatif bir
arayuzu haline getirir. Koltuk fiziksel kapasite yaratmaz; yayindaki sahnede
bir personeli temsil eder. Liste ve 3D akislari ayni musaitlik, guven ve onay
kurallarini kullanir.

## Backend

- `V15__versioned_shop_scenes.sql` ve `V16__shop_scene_tenant_integrity.sql`,
  `shop_scenes` ve `shop_chairs` tablolarini,
  tenant foreign key'lerini, RLS ve `FORCE ROW LEVEL SECURITY` politikalarini
  ekler. Her tenant icin tek taslak ve tek yayin surumu bulunabilir.
- Yeni tenant icin ilk yayin otomatik olusur. Ilk uc aktif personel, personel
  olusturulurken yayindaki/taslaktaki koltuklara atanir.
- Public manifest yalnizca `PUBLISHED` sahneyi dondurur. Personel adi model
  dosyasinda degil API yanitindadir.
- OWNER/MANAGER taslagi optimistic `revision` ile kaydeder, yayinlar veya
  gecmis bir surumu yeni bir surum olarak tekrar yayinlar. Yayin sonrasi yeni
  bir taslak otomatik acilir.
- Rezervasyon kaynagi `WEB_LIST` veya `WEB_3D` olarak kaydedilir ve kesinlesen
  appointment'a tasinir. `WEB_3D` istegi `sceneVersion + chairKey + staffId`
  uclusunu zorunlu tutar.
- Kayit aninda koltugun hala ayni personeli gosterdigi ve sahnenin hala
  yayinda oldugu tekrar sorgulanir. Eski sekme ya da degismis esleme
  `409 STALE_SCENE_SELECTION` ile durdurulur.
- NEW musteri ayni 3D istekten `PENDING_APPROVAL`, TRUSTED musteri tenant
  politikasi aciksa `AUTO_CONFIRMED` sonucunu alir.

## Frontend

- Musteri ekraninda Liste/3D segmented kontrolu ayni `staffId`, hizmet, tarih
  ve slot taslagini kullanir. Giris yonlendirmesi gorunum ve koltuk secimini de
  query string ile korur.
- React Three Fiber, Three.js ve Drei surumleri package lock'ta sabittir.
  Sahne `frameloop="demand"`, sinirli OrbitControls, tek canvas ve route bazli
  lazy chunk kullanir.
- Koltuk, projede uretilen `public/models/barber-chair.glb` dosyasindan gelir.
  Dosya 21 KB'dir; keyfi URL veya harici script kabul edilmez.
- Masaustunde secim paneli sahnenin yaninda, mobilde sahnenin altindadir.
  Form ve saat kontrolleri HTML olarak kalir.
- GLB/WebGL hatasi hata sinirinda tutulur; gorunum listeye duser ve mevcut
  personel/hizmet/tarih/saat secimleri silinmez.
- `/app/shop-design`, onayli sablon, renk, personel eslemesi, koltuk konumu,
  taslak kaydetme, yayinlama ve rollback islemlerini sunar.

## API

- `GET /api/public/v1/shops/{tenantSlug}/scene`
- `GET /api/shop-design`
- `PUT /api/shop-design/draft`
- `POST /api/shop-design/publish`
- `POST /api/shop-design/rollback`
- `POST /api/customer/v1/booking-requests` yeni `source`, `sceneVersion` ve
  `chairKey` alanlarini kabul eder. Eski istemciler icin eksik `source`
  `WEB_LIST` olarak yorumlanir.

Makinece okunabilir sozlesme `docs/contracts/booking-v1.openapi.yaml` icinde
`1.4.0-versioned-shop-scenes` surumundedir.

## Dogrulama kapisi

- Backend entegrasyon testi public manifesti, NEW ve TRUSTED 3D sonucunu,
  booking/appointment kaynak kaydini, yayin sonrasi eski sahnenin reddini ve
  sahne optimistic lock davranisini kapsar.
- Frontend testleri 3D rezervasyon payload'ini, taslak personel eslemesini,
  rol korumasini ve mevcut rezervasyon akisini kapsar.
- Production build, OpenAPI YAML parse, gercek canvas piksel kontrolu,
  canvas pointer secimi, kamera hareketi, GLB yuklenmesi, masaustu/mobil kadraj
  ve WebGL hata fallback'i yayin kapisinin parcasidir.
