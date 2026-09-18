import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import vm from 'node:vm';
import { beforeEach, describe, expect, test, vi } from 'vitest';

type Listener = (event: unknown) => void;
const ORIGIN = 'https://randevu.example.test';
const source = readFileSync(resolve(__dirname, '../public/sw.js'), 'utf8');

function loadWorker() {
  const listeners: Record<string, Listener> = {};
  const showNotification = vi.fn(() => Promise.resolve());
  const openWindow = vi.fn(() => Promise.resolve(null));
  const postMessage = vi.fn();
  const context = vm.createContext({
    self: {
      location: { origin: ORIGIN },
      addEventListener: (type: string, listener: Listener) => { listeners[type] = listener; },
      registration: { showNotification },
      clients: { matchAll: () => Promise.resolve([{ url: `${ORIGIN}/app`, postMessage }]), openWindow, claim: () => Promise.resolve() },
      skipWaiting: vi.fn(),
    },
    caches: { open: vi.fn(), keys: vi.fn(() => Promise.resolve([])), delete: vi.fn() },
    fetch: vi.fn(), Response, URL, Headers, Promise, JSON, String, console,
  });
  vm.runInContext(source, context);
  return { context: context as unknown as Record<string, (...args: unknown[]) => unknown>, listeners, showNotification, openWindow, postMessage };
}

function request(url: string, init: { method?: string; mode?: string; headers?: Record<string, string> } = {}) {
  return { url, method: init.method ?? 'GET', mode: init.mode ?? 'cors', headers: new Headers(init.headers ?? {}) };
}

describe('service worker önbellek politikası', () => {
  let worker: ReturnType<typeof loadWorker>;
  beforeEach(() => { worker = loadWorker(); });

  test('özel API yanıtları, yetkili istekler ve başka origin hiçbir zaman önbelleğe girmez', () => {
    const policy = (value: ReturnType<typeof request>) => worker.context.cachePolicy(value, ORIGIN);
    expect(policy(request(`${ORIGIN}/api/customer/v1/booking-requests`))).toBe('bypass');
    expect(policy(request(`${ORIGIN}/api/public/v1/shops/moda`))).toBe('bypass');
    expect(policy(request(`${ORIGIN}/actuator/metrics`))).toBe('bypass');
    expect(policy(request('http://localhost:8080/api/customer/v1/notifications'))).toBe('bypass');
    expect(policy(request(`${ORIGIN}/assets/index-abc.js`, { headers: { Authorization: 'Bearer secret' } }))).toBe('bypass');
    expect(policy(request(`${ORIGIN}/assets/index-abc.js`, { method: 'POST' }))).toBe('bypass');
    expect(policy(request(`${ORIGIN}/models/barber-chair.glb`, { headers: { Range: 'bytes=0-10' } }))).toBe('bypass');
  });

  test('yalnızca uygulama kabuğu, hash’li dosyalar ve ikonlar önbellek stratejisi alır', () => {
    const policy = (value: ReturnType<typeof request>) => worker.context.cachePolicy(value, ORIGIN);
    expect(policy(request(`${ORIGIN}/customer/account`, { mode: 'navigate' }))).toBe('navigation');
    expect(policy(request(`${ORIGIN}/assets/BookingScreen-1a2b.js`))).toBe('asset');
    expect(policy(request(`${ORIGIN}/icons/icon-192.png`))).toBe('static');
    expect(policy(request(`${ORIGIN}/models/barber-chair.glb`))).toBe('network');
  });

  test('no-store, private, çerez taşıyan ve başarısız yanıtlar saklanmaz', () => {
    const store = (response: Response) => worker.context.shouldStore(response);
    // Tarayıcıda aynı origin fetch yanıtı 'basic' tiptedir; Node'un Response nesnesi 'default' döndüğü için sahte yanıt kullanılır.
    const fake = (headers: Record<string, string>, init: { type?: string; status?: number } = {}) => ({
      ok: (init.status ?? 200) < 300, status: init.status ?? 200, type: init.type ?? 'basic', headers: new Map(Object.entries(headers)),
    }) as unknown as Response;
    expect(store(fake({ 'content-type': 'text/javascript' }))).toBe(true);
    expect(store(fake({ 'cache-control': 'no-store' }))).toBe(false);
    expect(store(fake({ 'cache-control': 'private, max-age=60' }))).toBe(false);
    expect(store(fake({ 'set-cookie': 'sid=1' }))).toBe(false);
    expect(store(fake({}, { status: 404 }))).toBe(false);
    expect(store(fake({}, { type: 'opaque' }))).toBe(false);
    expect(store(fake({}, { type: 'cors' }))).toBe(false);
  });
});

describe('service worker bildirimleri', () => {
  test('push yükü kısaltılır ve dış adreslere yönlendirme engellenir', async () => {
    const worker = loadWorker();
    let waited: Promise<unknown> | undefined;
    worker.listeners.push({
      data: { text: () => JSON.stringify({ title: 'Randevun onaylandı', body: 'Saç Kesimi · 19 Eylül 10:00', url: 'https://evil.example/phish', tag: 'BOOKING_REQUEST_APPROVED<script>' }) },
      waitUntil: (promise: Promise<unknown>) => { waited = promise; },
    });
    await waited;
    expect(worker.showNotification).toHaveBeenCalledWith('Randevun onaylandı', expect.objectContaining({
      body: 'Saç Kesimi · 19 Eylül 10:00', tag: 'BOOKING_REQUEST_APPROVEDscript', data: { url: '/' }, icon: '/icons/icon-192.png',
    }));
  });

  test('bozuk push verisi yine de güvenli genel bildirim gösterir', () => {
    const worker = loadWorker();
    const parsed = worker.context.parsePush('düz metin', ORIGIN) as { title: string; body: string; url: string };
    expect(parsed).toMatchObject({ title: 'Berber Randevu', body: 'düz metin', url: '/' });
    expect(worker.context.safeUrl('/app/booking-requests?x=1#top', ORIGIN)).toBe('/app/booking-requests?x=1#top');
    expect(worker.context.safeUrl('//evil.example/x', ORIGIN)).toBe('/');
    expect(worker.context.safeUrl('javascript:alert(1)', ORIGIN)).toBe('/');
  });

  test('bildirime tıklayınca aynı origin penceresi yoksa güvenli yol açılır', async () => {
    const worker = loadWorker();
    let waited: Promise<unknown> | undefined;
    const close = vi.fn();
    (worker.context.self as unknown as { clients: { matchAll: () => Promise<unknown[]> } }).clients.matchAll = () => Promise.resolve([]);
    worker.listeners.notificationclick({ notification: { close, data: { url: '/customer/account' } }, waitUntil: (promise: Promise<unknown>) => { waited = promise; } });
    await waited;
    expect(close).toHaveBeenCalled();
    expect(worker.openWindow).toHaveBeenCalledWith('/customer/account');
  });
});
