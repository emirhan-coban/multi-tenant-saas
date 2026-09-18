import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

vi.mock('../src/api/pwa', () => ({
  getPushConfig: vi.fn(),
  registerPushSubscription: vi.fn(),
  unregisterPushSubscription: vi.fn(),
  sendTestPush: vi.fn(),
  logoutEverywhere: vi.fn(),
}));

import { getPushConfig, registerPushSubscription, sendTestPush, unregisterPushSubscription } from '../src/api/pwa';
import { NotificationPermissionCard } from '../src/components/NotificationPermissionCard';
import { urlBase64ToUint8Array } from '../src/pwa/push';
import { dismissInstall, installDismissed } from '../src/pwa/install';

const publicKey = 'BI2dogXDC4jccDVvoQl-My77yzo9J_yQPACNmWH5rNH5EHRrB89d0n4Q4s0gnQnIx-4fw3AEmWfJyI7FRSXu-8Y';
type FakeSubscription = { endpoint: string; options: { applicationServerKey: ArrayBuffer }; toJSON: () => unknown; unsubscribe: ReturnType<typeof vi.fn> };

function installBrowser(permission: NotificationPermission, existing: FakeSubscription | null = null) {
  let current = existing;
  const subscription = (): FakeSubscription => ({
    endpoint: 'https://fcm.googleapis.com/fcm/send/device-1',
    options: { applicationServerKey: urlBase64ToUint8Array(publicKey).buffer },
    toJSON: () => ({ endpoint: 'https://fcm.googleapis.com/fcm/send/device-1', keys: { p256dh: 'p256', auth: 'auth' } }),
    unsubscribe: vi.fn(async () => { current = null; return true; }),
  });
  const pushManager = {
    getSubscription: vi.fn(async () => current),
    subscribe: vi.fn(async () => { current = subscription(); return current; }),
  };
  const requestPermission = vi.fn(async () => 'granted' as NotificationPermission);
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  Object.defineProperty(window, 'PushManager', { configurable: true, value: function PushManager() {} });
  Object.defineProperty(window, 'Notification', { configurable: true, value: { permission, requestPermission } });
  Object.defineProperty(navigator, 'serviceWorker', { configurable: true, value: { ready: Promise.resolve({ pushManager }) } });
  return { pushManager, requestPermission, subscription };
}

beforeEach(() => {
  vi.mocked(getPushConfig).mockResolvedValue({ enabled: true, publicKey });
  vi.mocked(registerPushSubscription).mockResolvedValue(undefined);
  vi.mocked(unregisterPushSubscription).mockResolvedValue(undefined);
  vi.mocked(sendTestPush).mockResolvedValue({ queued: 1 });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  for (const key of ['PushManager', 'Notification']) delete (window as unknown as Record<string, unknown>)[key];
  delete (navigator as unknown as Record<string, unknown>).serviceWorker;
});

test('VAPID anahtarı tarayıcının beklediği 65 baytlık diziye çevrilir', () => {
  const bytes = urlBase64ToUint8Array(publicKey);
  expect(bytes).toHaveLength(65);
  expect(bytes[0]).toBe(4);
});

test('desteklemeyen tarayıcıda izin istenmez ve alternatif anlatılır', async () => {
  render(<NotificationPermissionCard audience="customer" />);
  expect(await screen.findByText(/anlık bildirimleri desteklemiyor/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Bildirimleri aç' })).toBeNull();
  expect(getPushConfig).not.toHaveBeenCalled();
});

test('izin yalnızca tıklamayla istenir, abonelik sunucuya kaydedilir ve kapatılabilir', async () => {
  const browser = installBrowser('default');
  render(<NotificationPermissionCard audience="customer" />);
  const enable = await screen.findByRole('button', { name: 'Bildirimleri aç' });
  expect(browser.requestPermission).not.toHaveBeenCalled();

  fireEvent.click(enable);
  expect(await screen.findByText('Bildirimler bu cihazda açıldı.')).toBeTruthy();
  expect(browser.requestPermission).toHaveBeenCalledOnce();
  expect(browser.pushManager.subscribe).toHaveBeenCalledWith(expect.objectContaining({ userVisibleOnly: true }));
  expect(registerPushSubscription).toHaveBeenCalledWith('customer', {
    endpoint: 'https://fcm.googleapis.com/fcm/send/device-1', keys: { p256dh: 'p256', auth: 'auth' },
  });

  fireEvent.click(screen.getByRole('button', { name: 'Test bildirimi gönder' }));
  expect(await screen.findByText(/birkaç saniye içinde/)).toBeTruthy();

  fireEvent.click(screen.getByRole('button', { name: 'Bu cihazda kapat' }));
  expect(await screen.findByText('Bu cihazda bildirimler kapatıldı.')).toBeTruthy();
  expect(unregisterPushSubscription).toHaveBeenCalledWith('customer', 'https://fcm.googleapis.com/fcm/send/device-1');
});

test('engellenmiş izin için tarayıcı ayarı yönlendirmesi gösterilir', async () => {
  installBrowser('denied');
  render(<NotificationPermissionCard audience="staff" />);
  expect(await screen.findByText(/izni bu tarayıcıda engellenmiş/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Bildirimleri aç' })).toBeNull();
});

test('sunucu kaydı başarısız olursa tarayıcı aboneliği geri alınır', async () => {
  const browser = installBrowser('granted');
  vi.mocked(registerPushSubscription).mockRejectedValueOnce(new Error('offline'));
  render(<NotificationPermissionCard audience="staff" />);
  fireEvent.click(await screen.findByRole('button', { name: 'Bildirimleri aç' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  await waitFor(async () => expect(await browser.pushManager.getSubscription()).toBeNull());
});

test('mevcut abonelik açılışta sunucuyla yeniden eşitlenir', async () => {
  const browser = installBrowser('granted');
  await browser.pushManager.subscribe();
  vi.mocked(registerPushSubscription).mockClear();
  render(<NotificationPermissionCard audience="staff" />);
  expect(await screen.findByText('Bu cihazda açık')).toBeTruthy();
  await waitFor(() => expect(registerPushSubscription).toHaveBeenCalledWith('staff', expect.objectContaining({ endpoint: expect.any(String) })));
});

test('ana ekrana ekleme daveti kapatılınca 30 gün gösterilmez', () => {
  const now = Date.UTC(2026, 8, 17);
  localStorage.clear();
  expect(installDismissed(now)).toBe(false);
  dismissInstall(now);
  expect(installDismissed(now + 29 * 86_400_000)).toBe(true);
  expect(installDismissed(now + 31 * 86_400_000)).toBe(false);
});
