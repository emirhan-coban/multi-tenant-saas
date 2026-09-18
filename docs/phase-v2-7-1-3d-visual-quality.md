# Plan V2 Faz 7.1 - 3D gorsel kalite

Faz 7'nin rezervasyon altyapisi (koltuk -> personel eslemesi, surumlu sahne,
musaitlik, `WEB_3D` kaynagi) degismeden, musteri ekraninin sanat ve gorsel
katmani yenilendi. Backend ve API sozlesmesi etkilenmez.

## Model pipeline

- `web/scripts/generate-chair-model.mjs` modern hidrolik berber koltugu uretir:
  kanal dikisli deri oturak/sirtlik/bacak destegi, basluk, krom kolcak borulari,
  korugan hidrolik govde, krom taban ve kaymaz ayaklik.
- Tek kaynaktan iki GLB cikar: `barber-chair.glb` (masaustu, ~35k ucgen) ve
  `barber-chair-mobile.glb` (~11k ucgen). Geometriler malzeme bazinda
  birlestirilir; koltuk basina 4 draw call.
- Malzeme isim sozlesmesi: `Leather`, `Chrome`, `BlackMetal`, `Rubber`.
  Blender'da hazirlanan gercek bir model bu isimlerle `.glb` olarak ayni yola
  konursa kod degismeden calisir.
- Yeniden uretmek icin: `node scripts/generate-chair-model.mjs`

## Sahne

- `components/shop/ShopRoom.tsx`: terrazzo zemin, beton duvar, siyah cerceveli
  aynalar, tas tezgah + asma dolap, sicak LED isik cubuklari, ahsap lamel bolme,
  raf/poster, bitkiler, urun siseleri, bekleme masasi ve paspas.
- `components/shop/textures.ts`: tum dokular seed'li canvas ile uretilir; harici
  dosya/CDN yok, her yuklemede ayni gorunur.
- Isik: Lightformer tabanli ortam haritasi (krom yansimalari), yumusak golgeli
  gun isigi, koltuk basina tavan spotu, ContactShadows. Masaustunde zemin
  yansimasi (MeshReflectorMaterial) ve aynalar icin oda kup yansimasi.
- Koltuk durumlari: musait = marka rengi deri, secili = bordo deri + turkuaz
  kontur + zemin halkasi + guclu spot, dolu = gri deri + kisik spot,
  uzerine gelme = hafif turkuaz parlama.
- Etiketler sahnede HTML: bas harf avatari, `01 · Ad`, ilk musait saat ya da
  "Bugun dolu". Etiketler klavye ile secilebilen butonlardir.
- Kamera: acilista iceri suzulme, koltuk secilince yumusak yaklasma, dar
  ekranda secili koltuga odak; sifirla/yakinlas/uzaklas kontrolleri.
  `prefers-reduced-motion` aciksa animasyon atlanir.

## Performans ve yedek

- Kalite katmani: mobil/dokunmatik, <=4 cekirdek veya <=4 GB bellekte `low`
  (mobil GLB, golge/yansima yok, dusuk DPR). Masaustunde ilk ~90 karede kare
  suresi olculur, 28 fps altinda otomatik `low`'a duser.
  QA icin `?quality=high|low` ile zorlanabilir.
- `frameloop="demand"`: sahne yalnizca etkilesim/animasyon sirasinda cizilir.
- WebGL/GLB hatasi ayni hata sinirinda kalir; ayni tasarim dilinde 2D liste
  gorunumu (avatar, koltuk no, ilk musait saat) ile secimler korunur.

## Rezervasyon paneli

- 3 adim: Secim (hizmet, tarih, saat) -> Bilgiler (hesap, berbere not) -> Onay.
- Tum berberlerin musaitligi paralel alinir; sahnede ve listede ilk musait saat
  ve dolu durumu gosterilir.
- Giris yonlendirmesi `step=2` dahil taslagi korur.
