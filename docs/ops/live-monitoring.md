# Canlı izleme ve bakım (ücretsiz katman)

Otomatik olanlar (repoda):

- **`synthetic.yml`**: 6 saatte bir Playwright ile canlı demoyu sınar (API sağlığı, güvenlik başlıkları,
  `robots.txt`/`sitemap.xml`, giriş sayfası, herkese açık rezervasyon sayfasında 3B sahne + CSP hatası yok).
  Kırmızı olursa GitHub bildirim e-postası gelir. Elle: Actions → Synthetic → Run workflow.
- **`keepalive.yml`**: Render/Supabase uyumasın diye 14 dakikada bir sağlık ucunu çağırır.
- **Dependabot** (`.github/dependabot.yml`): haftalık, gruplu bağımlılık PR'ları; her biri normal CI'dan geçer.
- **Yedek/geri yükleme:** `ops/backup/` betikleri ve [`backup-restore.md`](backup-restore.md). Supabase ücretsiz
  planda otomatik günlük yedek yoktur; demo verisi `ops/demo/seed-demo.sh` ile yeniden üretilebilir.

Hesap sahibinin yapacakları (panel işleri):

1. **Uptime izleme:** UptimeRobot (ücretsiz) → `https://<render-adresi>/api/health` için 5 dk'lık HTTP(s) izleyici
   ve `https://<cloudflare-adresi>/` için bir tane daha; e-posta bildirimi açık.
2. **Cloudflare Previews:** Workers & Pages → proje → Settings → Builds → *Non-production branch builds* kapat
   (PR'larda kırmızı X çıkmasın). Production derlemesi etkilenmez.
3. **Hata izleme (isteğe bağlı):** Sentry ücretsiz plan; web için `@sentry/react`, backend için `sentry-spring-boot`
   eklenebilir. DSN değerleri yalnız Render/Cloudflare ortam değişkenlerine girilir, repoya yazılmaz.
4. **Upstash şifresi:** daha önce sohbette paylaşıldıysa yenileyin ve Render `APP_REDIS_URL`'i güncelleyin.
