import { expect, test } from '@playwright/test';

const API = process.env.E2E_API_URL ?? 'https://saas-backend-ms03.onrender.com';
const SLUG = process.env.E2E_SHOP_SLUG ?? 'demo-berber';

test('API sağlık ucu ayakta (soğuk başlangıç için bekler)', async ({ request }) => {
  await expect.poll(async () => (await request.get(`${API}/api/health`, { timeout: 60_000 })).status(),
    { timeout: 80_000, intervals: [3_000] }).toBe(200);
});

test('güvenlik başlıkları ve önbellek ilkesi yayında', async ({ request }) => {
  const res = await request.get('/');
  const h = res.headers();
  expect(h['content-security-policy']).toContain("default-src 'self'");
  expect(h['x-frame-options']).toBe('DENY');
  expect(h['x-content-type-options']).toBe('nosniff');
});

test('statik SEO dosyaları gerçek içerik döner (SPA fallback değil)', async ({ request }) => {
  expect(await (await request.get('/robots.txt')).text()).toContain('User-agent');
  expect(await (await request.get('/sitemap.xml')).text()).toContain('<urlset');
  expect((await request.get('/favicon.ico')).headers()['content-type']).toContain('icon');
});

test('giriş sayfası açılır', async ({ page }) => {
  await page.goto('/login');
  await expect(page.locator('input[type="password"]')).toBeVisible();
});

test('herkese açık rezervasyon sayfasında 3B sahne çizilir ve CSP hatası yok', async ({ page }) => {
  const cspErrors: string[] = [];
  page.on('console', m => { if (/Content Security Policy/i.test(m.text())) cspErrors.push(m.text()); });
  await page.goto(`/book/${SLUG}`);
  await expect(page.locator('canvas').first()).toBeVisible({ timeout: 60_000 });
  expect(cspErrors).toEqual([]);
});
