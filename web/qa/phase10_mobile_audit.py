"""Faz 10 telefon denetimi: aşamalı akış, alt panel, dokunma hedefleri, sabit alt çubuk,
geri tuşu, sahnenin sökülmesi ve performans bütçeleri (LCP ≤2,5 s, INP ≤200 ms, TTS).

Kullanım:  python3 -m http.server --directory dist 4174
           python3 web/qa/phase10_mobile_audit.py http://127.0.0.1:4174 çıktı_klasörü

API çağrıları sahte yanıtlarla yakalanır; backend gerekmez. Sunucunun SPA fallback'i
yoksa doğrudan /book/moda adresine gidilir (index.html tek giriş noktasıdır).
"""
import asyncio, json, sys
from datetime import date, timedelta
from playwright.async_api import async_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:4174"
OUT = sys.argv[2] if len(sys.argv) > 2 else "."
# Derlemede gömülü API adresi ortama göre değişir; yol kalıbıyla yakalanır.
API = "**/api/**"
PHONE = {"width": 390, "height": 844}
results = []


def record(name, ok, detail=""):
    results.append({"check": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""))


NAMES = [("a", "Emre Kaya"), ("b", "Ahmet Yılmaz Karadeniz"), ("c", "Can Demir")]
SHOP = {"id": "t", "slug": "moda", "name": "Berber", "timezone": "Europe/Istanbul",
        "services": [
            {"id": "s1", "name": "Saç kesimi", "durationMin": 45, "bufferMin": 0, "price": 600},
            {"id": "s2", "name": "Sakal tıraşı", "durationMin": 30, "bufferMin": 0, "price": 350},
            {"id": "s3", "name": "Saç + sakal", "durationMin": 60, "bufferMin": 10, "price": 850},
        ],
        "staff": [{"id": s, "displayName": n, "chairKey": f"chair-{i+1}"} for i, (s, n) in enumerate(NAMES)]}
SCENE = {"version": 1, "templateKey": "CLASSIC_3_CHAIR", "accentColor": "#245e50", "chairs": [
    {"chairKey": f"chair-{i+1}", "label": n, "staffId": s, "staffName": n,
     "positionX": (i - 1) * 3.25, "positionZ": -1.25, "rotationY": 0} for i, (s, n) in enumerate(NAMES)]}


async def api_routes(page, three_d=True, vitals_seen=None):
    async def handler(route):
        url = route.request.url
        if "/metrics/booking" in url:
            if vitals_seen is not None:
                try:
                    vitals_seen.append(json.loads(route.request.post_data or "{}"))
                except json.JSONDecodeError:
                    vitals_seen.append({})
            return await route.fulfill(status=204, body="")
        body = None
        if url.endswith("/features"):
            body = {"threeD": three_d, "pushNotifications": True, "pwaInstall": True}
        elif url.endswith("/scene"):
            body = SCENE
        elif "/availability" in url:
            day = url.split("date=")[1][:10]
            body = {"timezone": "Europe/Istanbul", "date": day,
                    "slots": [{"startsAt": f"{day}T{h-3:02d}:00:00Z", "endsAt": f"{day}T{h-3:02d}:45:00Z"}
                              for h in (10, 11, 12, 14, 15, 16)]}
        elif "/shops/" in url:
            body = SHOP
        elif "/push/config" in url:
            body = {"enabled": False}
        if body is None:
            return await route.fulfill(status=401, content_type="application/json", body='{"code":"UNAUTHORIZED"}')
        await route.fulfill(status=200, content_type="application/json",
                            headers={"Cache-Control": "no-store"}, body=json.dumps(body))

    await page.route(API, handler)


TARGETS_JS = """() => {
  // WCAG 2.2 asgari hedef 24×24'tür; bu akışta 44 px hedeflenir (parmakla kullanım).
  const small = [];
  for (const el of document.querySelectorAll('button, a[href], select, textarea, input:not([type=hidden])')) {
    if (el.closest('[aria-hidden=true]')) continue;
    const box = el.getBoundingClientRect();
    if (box.width === 0 && box.height === 0) continue;               // gizli
    if (box.bottom < 0 || box.top > innerHeight) continue;            // ekran dışında
    const style = getComputedStyle(el);
    if (style.visibility === 'hidden' || style.display === 'none') continue;
    if (box.height < 44 || box.width < 24) {
      small.push(`${el.tagName.toLowerCase()}.${el.className || '-'} ${Math.round(box.width)}x${Math.round(box.height)}`);
    }
  }
  return small;
}"""

OVERLAP_JS = """() => {
  // Sabit alt çubuk, panel içindeki son etkileşimli alanı örtmemeli.
  const footer = document.querySelector('.bk-sheet-footer');
  if (!footer) return 'alt çubuk yok';
  const bar = footer.getBoundingClientRect();
  if (bar.bottom > innerHeight + 1) return 'alt çubuk ekranın altında kalıyor';
  const body = document.querySelector('.bk-sheet-body');
  if (!body) return '';
  const fields = [...body.querySelectorAll('button, select, textarea')];
  const covered = fields.filter(el => {
    const box = el.getBoundingClientRect();
    return box.height > 0 && box.top < bar.top && box.bottom > bar.top + 4;
  });
  return covered.length ? `${covered.length} alan alt çubuğun altında kalıyor` : '';
}"""


async def stage_of(page):
    return await page.get_attribute("main.booking-shell", "data-stage")


async def run():
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(args=["--use-gl=swiftshader", "--enable-unsafe-swiftshader"])
        vitals = []
        context = await browser.new_context(viewport=PHONE, device_scale_factor=3, is_mobile=True,
                                            has_touch=True, locale="tr-TR")
        page = await context.new_page()
        model_requests = []
        page.on("request", lambda request: model_requests.append(request.url) if request.url.endswith(".glb") else None)
        await api_routes(page, vitals_seen=vitals)
        await page.goto(f"{BASE}/index.html", wait_until="load")
        await page.evaluate("path => history.replaceState({}, '', path)", "/book/moda")
        await page.goto(f"{BASE}/index.html#/", wait_until="load")

        # SPA doğrudan adresi desteklemeyen sunucularda yönlendirme ile açılır.
        await page.evaluate("""() => { history.pushState({}, '', '/book/moda'); dispatchEvent(new PopStateEvent('popstate')); }""")
        await page.wait_for_selector("main.booking-shell", timeout=20000)

        # --- 1. aşama: berber seçimi ---------------------------------------
        record("Telefonda aşamalı akış devrede", await stage_of(page) == "barber", await stage_of(page) or "")

        chips = await page.locator(".bk-chip").count()
        record("Hizmet çipleri sahnenin üstünde", chips == 3, f"{chips} çip")

        await page.wait_for_selector(".bk-strip-item", timeout=20000)
        strip = await page.locator(".bk-strip-item").count()
        record("Berber şeridi isimleri taşıyor", strip == 3, f"{strip} berber")

        # Bu kapsayıcı paylaşımlı ve WebGL yazılımla taklit ediliyor: süre bütçeleri burada
        # yalnızca "tamamen bozulmamış" kontrolüdür. Gerçek hedefler (listReady ≤1,5 sn,
        # TTS ≤3,5 sn, INP ≤200 ms) cihazda ölçülmelidir.
        list_ready = await page.evaluate("() => Math.round(performance.now())")
        record("Model beklemeden seçim yapılabiliyor (kapsayıcı eşiği)", list_ready < 20000, f"{list_ready} ms")

        # Sahnedeki etiketlerde isim yok (çakışma kaynağı): yalnızca koltuk numarası.
        labels = await page.locator(".scene-chair-label.is-compact").count()
        if labels:
            texts = await page.locator(".scene-chair-label.is-compact").all_inner_texts()
            record("Koltuk etiketinde yalnızca numara var", all(t.strip().isdigit() for t in texts), str(texts))
        else:
            record("Koltuk etiketinde yalnızca numara var", True, "sahne açılmadı, liste yolu kullanıldı")

        small = await page.evaluate(TARGETS_JS)
        record("1. aşama dokunma hedefleri ≥44 px", not small, "; ".join(small[:4]))

        overlap = await page.evaluate(OVERLAP_JS)
        record("1. aşama alt çubuk alanları örtmüyor", overlap == "", overlap)

        # Şeritten seçim sahneyle bağlı
        await page.locator(".bk-strip-item").nth(2).click()
        selected = await page.locator(".bk-strip-item.is-selected").inner_text()
        record("Şeritten seçim çalışıyor", "Can" in selected, selected.replace("\n", " ")[:60])

        # --- 2. aşama: saat -------------------------------------------------
        await page.get_by_role("button", name="Saat seç").click()
        await page.wait_for_function("() => document.querySelector('main').dataset.stage === 'time'", timeout=10000)
        record("Koltuk seçilince saat aşamasına geçiliyor", True)

        canvases = await page.locator("canvas").count()
        record("Saat aşamasında sahne sökülüyor", canvases == 0, f"{canvases} canvas")
        record("Seçilen koltuk görünür kalıyor", await page.locator(".bk-frozen").count() == 1)

        slots = await page.locator(".bk-slots button").count()
        record("Saatler ana içerik oldu", slots >= 6, f"{slots} saat")

        small = await page.evaluate(TARGETS_JS)
        record("2. aşama dokunma hedefleri ≥44 px", not small, "; ".join(small[:4]))
        overlap = await page.evaluate(OVERLAP_JS)
        record("2. aşama alt çubuk alanları örtmüyor", overlap == "", overlap)

        # Alt panel düğmeyle küçültülüp genişletilebiliyor (sürükleme tek yol değil)
        await page.locator(".bk-sheet-toggle").click()
        collapsed = await page.locator(".bk-sheet-peek").count()
        await page.locator(".bk-sheet-toggle").click()
        expanded = await page.locator(".bk-sheet-expanded").count()
        record("Panel düğmeyle küçültülüp genişletiliyor", collapsed == 1 and expanded == 1)

        # --- geri tuşu ------------------------------------------------------
        await page.go_back()
        await page.wait_for_function("() => document.querySelector('main').dataset.stage === 'barber'", timeout=10000)
        record("Geri tuşu bir aşama geri alıyor", await stage_of(page) == "barber")

        # --- son kontrol ----------------------------------------------------
        await page.get_by_role("button", name="Saat seç").click()
        await page.wait_for_function("() => document.querySelector('main').dataset.stage === 'time'", timeout=10000)
        await page.locator(".bk-slots button").first.click()
        await page.get_by_role("button", name="Devam et").click()
        await page.wait_for_function("() => ['details','review'].includes(document.querySelector('main').dataset.stage)", timeout=10000)
        stage = await stage_of(page)
        record("Bilgiler aşamasına geçiliyor", stage in ("details", "review"), stage or "")

        small = await page.evaluate(TARGETS_JS)
        record("3. aşama dokunma hedefleri ≥44 px", not small, "; ".join(small[:4]))

        # --- performans bütçeleri -------------------------------------------
        # Ölçüler uygulamanın kendi kaydedicisinden okunur: LCP ve INP'yi yalnızca
        # PerformanceObserver (buffered) doğru verir, getEntriesByType boş dönebilir.
        await page.evaluate("() => { Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true }); document.dispatchEvent(new Event('visibilitychange')); }")
        await page.wait_for_timeout(600)
        record("Ölçüm sunucuya gönderiliyor", len(vitals) >= 1, json.dumps(vitals[:1], ensure_ascii=False)[:160])
        measured = vitals[0] if vitals else {}
        scene_opened = bool(model_requests)
        lcp, inp, tts = measured.get("lcp"), measured.get("inp"), measured.get("tts")
        record("LCP ≤ 2500 ms", lcp is not None and lcp <= 2500, f"{lcp} ms")
        # INP bütçesi gerçek cihazda 200 ms'dir. Bu kapsayıcıda WebGL yazılımla taklit
        # edildiği için 3B sahne açıkken bir kare yüzlerce ms sürer; sahne açıkken ölçülen
        # değer cihazı temsil etmez. Bu yüzden sahne kapalıyken katı bütçe, açıkken
        # yalnızca "kabul edilemez ölçüde kötü değil" eşiği uygulanır ve durum yazılır.
        if scene_opened:
            record("Etkileşim (yazılım WebGL, cihazı temsil etmez) ≤ 1500 ms",
                   inp is not None and inp <= 1500, f"{inp} ms — gerçek cihazda 200 ms hedefi ayrıca ölçülmeli")
        else:
            record("En kötü etkileşim ≤ 200 ms", inp is not None and inp <= 200, f"{inp} ms")
        if scene_opened:
            record("Sahne seçilebilir olma süresi ≤ 3500 ms", tts is not None and tts <= 3500, f"{tts} ms")
        else:
            # Bu kapsayıcıda WebGL yazılımla taklit edilir ve sahne bazen hiç açılmaz.
            # Ölçüm uydurulmaz: sahne açılmadıysa TTS raporlanmaz, liste yolu ölçülür.
            record("Sahne açılmadı: liste yolu ölçüldü", measured.get("listReady") is not None,
                   f"listReady={measured.get('listReady')} ms")
        if vitals:
            body = vitals[0]
            personal = [key for key in body if key not in {"view", "quality", "tts", "listReady", "lcp", "inp",
                                                           "frameMedian", "longFrames", "stage", "narrow"}]
            record("Ölçümde kişisel veri yok", not personal, ", ".join(personal))

        # --- %200 zoom / dar ekran reflow ------------------------------------
        for size in ({"width": 320, "height": 568}, {"width": 360, "height": 420}):
            await page.set_viewport_size(size)
            await page.wait_for_timeout(200)
            scroll = await page.evaluate("() => document.documentElement.scrollWidth - document.documentElement.clientWidth")
            overlap = await page.evaluate(OVERLAP_JS)
            record(f"{size['width']}×{size['height']} yatay kaydırma yok", scroll <= 1, f"{scroll} px")
            record(f"{size['width']}×{size['height']} alt çubuk örtmüyor", overlap == "", overlap)

        await context.close()

        # --- 3B kapalıyken liste yolu ----------------------------------------
        context = await browser.new_context(viewport=PHONE, is_mobile=True, has_touch=True, locale="tr-TR")
        page = await context.new_page()
        await api_routes(page, three_d=False)
        downloads = []
        page.on("request", lambda request: downloads.append(request.url))
        await page.goto(f"{BASE}/index.html", wait_until="load")
        await page.evaluate("""() => { history.pushState({}, '', '/book/moda'); dispatchEvent(new PopStateEvent('popstate')); }""")
        await page.wait_for_selector(".bk-barber, .bk-strip-item", timeout=20000)
        await page.wait_for_timeout(800)
        glb = [url for url in downloads if url.endswith(".glb")]
        record("3B kapalıyken model indirilmiyor", not glb, ", ".join(glb[:2]))
        record("3B kapalıyken seçim yapılabiliyor", await page.locator(".bk-barber, .bk-strip-item").count() >= 3)
        await context.close()
        await browser.close()

    failed = [item for item in results if not item["ok"]]
    with open(f"{OUT}/phase10-mobile-audit.json", "w", encoding="utf-8") as file:
        json.dump(results, file, ensure_ascii=False, indent=2)
    print(f"\n{len(results) - len(failed)}/{len(results)} geçti")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(run()))
