import { defineConfig } from '@playwright/test';

// Sentetik (canlı) denetim: salt okunur; hesap açmaz, veri yazmaz.
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  retries: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://multi-tenant-saas.emirhancob4n.workers.dev',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
