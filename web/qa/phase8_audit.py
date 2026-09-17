"""Faz 8 tarayıcı denetimi: PWA/SW, özel veri önbelleği, klavye, erişilebilir adlar,
%200 zoom (reflow), düşük donanım ve 3D kill switch. Üretim derlemesine karşı çalışır.

Kullanım:  npx vite build && python3 -m http.server --directory dist 4174  (SPA fallback gerekir)
           python3 web/qa/phase8_audit.py http://127.0.0.1:4174 çıktı_klasörü
API çağrıları sahte yanıtlarla yakalanır; backend gerekmez.
"""
import asyncio, json, sys, time
from datetime import date, timedelta
from playwright.async_api import async_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:4174"
OUT = sys.argv[2] if len(sys.argv) > 2 else "."
API = "http://localhost:8080"
results = []

def record(name, ok, detail=""):
    results.append({"check": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""))

SHOP = {"id": "t", "slug": "moda", "name": "Berber", "timezone": "Europe/Istanbul",
        "services": [{"id": "s1", "name": "Saç kesimi", "durationMin": 45, "bufferMin": 0, "price": 600}],
        "staff": [{"id": s, "displayName": n, "chairKey": f"chair-{i+1}"} for i, (s, n) in enumerate([("a", "Emre Kaya"), ("b", "Ahmet Yılmaz"), ("c", "Can Demir")])]}
SCENE = {"version": 1, "templateKey": "CLASSIC_3_CHAIR", "accentColor": "#245e50", "chairs": [
    {"chairKey": f"chair-{i+1}", "label": n, "staffId": s, "staffName": n, "positionX": (i - 1) * 3.25, "positionZ": -1.25, "rotationY": 0}
    for i, (s, n) in enumerate([("a", "Emre Kaya"), ("b", "Ahmet Yılmaz"), ("c", "Can Demir")])]}

async def api_routes(page, three_d=True, private_seen=None):
    async def handler(route):
        url = route.request.url
        body = None
        if url.endswith("/features"): body = {"threeD": three_d, "pushNotifications": True, "pwaInstall": True}
        elif url.endswith("/scene"): body = SCENE
        elif "/availability" in url:
            d = url.split("date=")[1][:10]
            body = {"timezone": "Europe/Istanbul", "date": d, "slots": [{"startsAt": f"{d}T{h-3:02d}:00:00Z", "endsAt": f"{d}T{h-3:02d}:45:00Z"} for h in (10, 11, 12, 14, 15, 16)]}
        elif "/shops/" in url: body = SHOP
        elif "/push/config" in url: body = {"enabled": False}
        if body is None:
            return await route.fulfill(status=401, content_type="application/json", body='{"code":"UNAUTHORIZED"}')
        await route.fulfill(status=200, content_type="application/json", headers={"Cache-Control": "no-store"}, body=json.dumps(body))
    await page.route(f"{API}/**", handler)

A11Y_JS = """() => {
  const out = [];
  const name = el => (el.getAttribute('aria-label') || el.getAttribute('aria-labelledby') && document.getElementById(el.getAttribute('aria-labelledby'))?.textContent
     || (el.labels && [...el.labels].map(l => l.textContent).join(' ')) || el.getAttribute('title') || el.textContent || el.getAttribute('alt') || '').trim();
  for (const el of document.querySelectorAll('button, a[href], input:not([type=hidden]), select, textarea, [role=button], [tabindex]')) {
    if (el.closest('[aria-hidden=true]')) continue;
    if (!name(el) && el.tagName !== 'MAIN' && el.getAttribute('tabindex') !== '-1') out.push('adsız: ' + el.outerHTML.slice(0, 90));
    const t = Number(el.getAttribute('tabindex')); if (t > 0) out.push('pozitif tabindex: ' + el.outerHTML.slice(0, 60));
  }
  for (const img of document.querySelectorAll('img')) if (!img.hasAttribute('alt')) out.push('alt yok: ' + img.src);
  if (!document.documentElement.lang) out.push('html lang yok');
  const ids = {}; for (const el of document.querySelectorAll('[id]')) { if (ids[el.id]) out.push('tekrar eden id: ' + el.id); ids[el.id] = 1; }
  return out;
}"""

CONTRAST_JS = """(selectors) => {
  const parse = c => (c.match(/[\\d.]+/g) || []).map(Number);
  const lum = ([r, g, b]) => { const f = v => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; }; return .2126 * f(r) + .7152 * f(g) + .0722 * f(b); };
  const bg = el => { while (el) { const c = parse(getComputedStyle(el).backgroundColor); if (c.length >= 3 && (c[3] === undefined || c[3] > .9)) return c; el = el.parentElement; } return [255, 255, 255]; };
  const res = [];
  for (const sel of selectors) for (const el of document.querySelectorAll(sel)) {
    if (!el.textContent.trim() || el.offsetParent === null) continue;
    const cs = getComputedStyle(el); if (Number(cs.opacity) < 1) continue;
    const a = lum(parse(cs.color)), b = lum(bg(el)); const ratio = (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    const size = parseFloat(cs.fontSize), bold = Number(cs.fontWeight) >= 700; const need = size >= 24 || (bold && size >= 18.66) ? 3 : 4.5;
    if (ratio < need) res.push(`${sel} "${el.textContent.trim().slice(0, 30)}" ${ratio.toFixed(2)} < ${need}`);
  }
  return res;
}"""

async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(args=["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"])

        # 1) PWA + service worker + özel veri önbelleği + offline kabuk
        ctx = await browser.new_context(viewport={"width": 1280, "height": 800})
        page = await ctx.new_page()
        await api_routes(page)
        await page.goto(f"{BASE}/book/moda?view=list")
        await page.wait_for_selector("text=Berberini seç", timeout=20000)
        manifest = await page.evaluate("fetch('/manifest.webmanifest').then(r => r.json())")
        icons_ok = await page.evaluate("Promise.all(['/icons/icon-192.png','/icons/icon-512.png','/icons/icon-maskable-512.png'].map(u => fetch(u).then(r => r.ok))).then(a => a.every(Boolean))")
        record("manifest yüklenebilir ve kurulum alanları tam", manifest.get("display") == "standalone" and manifest.get("start_url") and icons_ok
               and any(i.get("purpose") == "maskable" for i in manifest["icons"]), f"start_url={manifest.get('start_url')}")
        await page.evaluate("navigator.serviceWorker.ready")
        await page.reload(); await page.wait_for_selector("text=Berberini seç")
        controlled = await page.evaluate("!!navigator.serviceWorker.controller")
        record("service worker kaydoldu ve sayfayı kontrol ediyor", controlled)
        cached = await page.evaluate("""async () => { const out = []; for (const k of await caches.keys()) { const c = await caches.open(k); for (const r of await c.keys()) out.push(r.url); } return out; }""")
        private = [u for u in cached if "/api/" in u or API in u or "actuator" in u]
        record("önbellekte hiçbir API/özel yanıt yok", not private and len(cached) > 0, f"{len(cached)} kayıt; özel={private}")
        await ctx.set_offline(True)
        await page.goto(f"{BASE}/customer/account")
        try: await page.wait_for_function("document.body.innerText.trim().length > 20", timeout=10000)
        except Exception: pass
        text = await page.inner_text("body")
        record("çevrimdışı gezinmede uygulama kabuğu/offline sayfası açılır", "İnternet bağlantısı yok" in text or "Müşteri" in text or "giriş" in text.lower(), text[:80].replace("\n", " "))
        await ctx.set_offline(False)
        await page.goto(f"{BASE}/book/moda?view=list"); await page.wait_for_selector("text=Berberini seç")
        await ctx.set_offline(True); await page.evaluate("window.dispatchEvent(new Event('offline'))")
        await page.wait_for_timeout(300)
        record("çevrimdışı bandı rezervasyon panelinde görünür", await page.locator("#booking-panel .offline-banner").count() == 1)
        await ctx.set_offline(False)
        await ctx.close()

        # 2) Klavye + ekran okuyucu yapısı (liste görünümü)
        ctx = await browser.new_context(viewport={"width": 1280, "height": 800}, reduced_motion="reduce")
        page = await ctx.new_page(); await api_routes(page)
        await page.goto(f"{BASE}/book/moda?view=list"); await page.wait_for_selector("text=Berberini seç")
        await page.keyboard.press("Tab")
        first = await page.evaluate("document.activeElement.textContent")
        record("ilk Tab 'Randevu paneline geç' atlama bağlantısı", "Randevu paneline geç" in first, first)
        await page.keyboard.press("Enter")
        await page.keyboard.press("Tab")
        inside = await page.evaluate("!!document.activeElement.closest('#booking-panel')")
        record("atlama bağlantısı odağı panele taşır", inside, await page.evaluate("document.activeElement.outerHTML.slice(0,80)"))
        # yalnızca klavye ile seçim akışı
        await page.focus(".bk-barber >> nth=0"); await page.keyboard.press("Enter")
        await page.focus(".bk-slots button >> nth=3"); await page.keyboard.press("Space")
        await page.focus(".bk-primary"); await page.keyboard.press("Enter")
        await page.wait_for_timeout(300)
        focus_id = await page.evaluate("document.activeElement.id")
        record("klavyeyle berber+saat+devam; adım değişince odak panel başlığında", focus_id == "booking-panel-title", focus_id)
        outline = await page.evaluate("""() => { const b = document.querySelector('.bk-back') || document.querySelector('.bk-primary'); b.focus({focusVisible: true}); const s = getComputedStyle(b); return s.outlineStyle + ' ' + s.outlineWidth; }""")
        issues = await page.evaluate(A11Y_JS)
        record("erişilebilir ad / tabindex / alt / tekrar eden id denetimi", not issues, "; ".join(issues[:6]))
        snapshot = await page.locator("#booking-panel").aria_snapshot()
        open(f"{OUT}/aria-booking-panel.txt", "w").write(snapshot)
        record("panel erişilebilirlik ağacı başlık, adım listesi ve düğmeleri içerir", "heading \"Randevun\"" in snapshot and "listitem" in snapshot, f"{len(snapshot.splitlines())} satır")
        low = await page.evaluate(CONTRAST_JS, [".bk-panel p", ".bk-panel span", ".bk-panel small", ".bk-panel button", ".bk-barber small", ".bk-barber em", ".bk-list-head span", ".bk-steps li"])
        record("metin kontrastı WCAG AA", not low, "; ".join(low[:8]))
        await page.screenshot(path=f"{OUT}/keyboard-step2.png")
        await ctx.close()

        # 3) %200 zoom (reflow): 1280x800 ve 1920x1080 ekranların CSS pikselleri
        for w, h, label in [(640, 400, "1280x800@200%"), (960, 540, "1920x1080@200%"), (320, 568, "320px mobil")]:
            ctx = await browser.new_context(viewport={"width": w, "height": h}, reduced_motion="reduce")
            page = await ctx.new_page(); await api_routes(page)
            await page.goto(f"{BASE}/book/moda?view=list"); await page.wait_for_selector("text=Berberini seç")
            await page.click(".bk-slots button >> nth=0")
            overflow = await page.evaluate("document.documentElement.scrollWidth - window.innerWidth")
            btn = page.locator(".bk-primary"); await btn.scroll_into_view_if_needed()
            box = await btn.bounding_box()
            reachable = box and box["y"] >= 0 and box["y"] + box["height"] <= h + 1 and box["x"] >= 0 and box["x"] + box["width"] <= w + 1
            record(f"zoom/reflow {label}: yatay kaydırma yok, onay düğmesine ulaşılır", overflow <= 1 and reachable, f"taşma={overflow}px düğme={box}")
            await page.screenshot(path=f"{OUT}/zoom-{w}x{h}.png", full_page=True)
            await ctx.close()

        # 4) Düşük donanım: 2 çekirdek / 2 GB, CPU 6x yavaş → mobil model + low kalite
        ctx = await browser.new_context(viewport={"width": 1280, "height": 800}, reduced_motion="reduce")
        await ctx.add_init_script("Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>2});Object.defineProperty(navigator,'deviceMemory',{get:()=>2});")
        page = await ctx.new_page(); await api_routes(page)
        models = []
        page.on("request", lambda r: models.append(r.url) if r.url.endswith(".glb") else None)
        cdp = await ctx.new_cdp_session(page); await cdp.send("Emulation.setCPUThrottlingRate", {"rate": 6})
        start = time.time()
        await page.goto(f"{BASE}/book/moda")
        await page.wait_for_selector(".barber-scene.is-ready", timeout=120000)
        elapsed = time.time() - start
        quality = await page.evaluate("document.querySelector('[data-quality]')?.dataset.quality")
        record("düşük donanımda low kalite ve yalnızca mobil GLB", quality == "low" and models and all("mobile" in m for m in models), f"quality={quality} modeller={[m.split('/')[-1] for m in models]} süre={elapsed:.1f}s (CPU x6, yazılım GPU)")
        labels = await page.locator(".scene-chair-label").count()
        record("düşük donanımda koltuk etiketleri klavyeyle seçilebilir buton", labels == 3)
        await page.screenshot(path=f"{OUT}/low-hardware.png")
        await ctx.close()

        # 5) 3D kill switch: liste modu, GLB indirilmez, 3D sekmesi pasif
        ctx = await browser.new_context(viewport={"width": 1280, "height": 800}, reduced_motion="reduce")
        page = await ctx.new_page(); await api_routes(page, three_d=False)
        models = []
        page.on("request", lambda r: models.append(r.url) if r.url.endswith(".glb") or "BarberShopScene" in r.url else None)
        await page.goto(f"{BASE}/book/moda"); await page.wait_for_selector("text=Berberini seç")
        await page.wait_for_timeout(1500)
        disabled = await page.locator(".bk-view-tabs button >> nth=0").is_disabled()
        record("3D kapalı: liste modu açılır, 3D sekmesi pasif, 3D kodu/modeli indirilmez", disabled and await page.locator(".barber-scene").count() == 0 and not models, f"indirilen={models}")
        await page.screenshot(path=f"{OUT}/kill-switch-list.png")
        await ctx.close()
        await browser.close()
    json.dump(results, open(f"{OUT}/phase8-audit.json", "w"), ensure_ascii=False, indent=2)
    failed = [r for r in results if not r["ok"]]
    print(f"\n{len(results) - len(failed)}/{len(results)} kontrol geçti")
    sys.exit(1 if failed else 0)

asyncio.run(main())
