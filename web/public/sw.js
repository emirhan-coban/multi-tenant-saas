/* Berber Randevu service worker — Faz 8.
 *
 * Güvenlik kuralları (değiştirmeden önce docs/phase-8-pwa-notifications-rollout.md okuyun):
 *  - Yalnızca aynı origin'den gelen GET istekleri incelenir. API başka origin'de olsa bile /api/ ve /actuator/
 *    yolları, Authorization başlığı taşıyan her istek ve no-store/private yanıtlar ASLA önbelleğe alınmaz.
 *  - Önbellekte yalnızca uygulama kabuğu (SPA index), hash'li derleme dosyaları, ikonlar ve çevrimdışı sayfası tutulur.
 *  - Bildirim tıklaması yalnızca aynı origin içindeki yollara yönlendirir.
 */
const VERSION = '2026-09-17.1';
const SHELL_CACHE = 'berber-shell-' + VERSION;
const ASSET_CACHE = 'berber-assets-' + VERSION;
const APP_SHELL_KEY = '/__app-shell';
const OFFLINE_URL = '/offline.html';
const PRECACHE = [OFFLINE_URL, '/manifest.webmanifest', '/icons/icon-192.png', '/icons/icon-512.png', '/icons/badge-96.png'];
const MAX_ASSET_ENTRIES = 80;
const PRIVATE_PATH_PREFIXES = ['/api/', '/actuator/', '/customer/v1/'];

function isPrivatePath(pathname) {
  return PRIVATE_PATH_PREFIXES.some(function (prefix) { return pathname.startsWith(prefix); });
}

/** İstek için strateji: 'bypass' (service worker karışmaz), 'navigation', 'asset', 'static', 'network'. */
function cachePolicy(request, origin) {
  if (request.method !== 'GET') return 'bypass';
  var url;
  try { url = new URL(request.url); } catch (error) { return 'bypass'; }
  if (url.origin !== origin) return 'bypass';
  if (isPrivatePath(url.pathname)) return 'bypass';
  if (request.headers && request.headers.has('authorization')) return 'bypass';
  if (request.headers && request.headers.has('range')) return 'bypass';
  if (request.mode === 'navigate') return 'navigation';
  if (url.pathname.startsWith('/assets/')) return 'asset';
  if (url.pathname.startsWith('/icons/') || url.pathname === '/manifest.webmanifest' || url.pathname === OFFLINE_URL) return 'static';
  return 'network';
}

/** Yanıt önbelleğe yazılabilir mi? Özel veri riskini taşıyan her şey reddedilir. */
function shouldStore(response) {
  if (!response || !response.ok || response.status !== 200) return false;
  if (response.type && response.type !== 'basic') return false;
  var cacheControl = (response.headers && response.headers.get('cache-control')) || '';
  if (/no-store|private/i.test(cacheControl)) return false;
  if (response.headers && response.headers.has('set-cookie')) return false;
  return true;
}

function safeUrl(value, origin) {
  try {
    var url = new URL(typeof value === 'string' ? value : '/', origin);
    if (url.origin !== origin) return '/';
    return url.pathname + url.search + url.hash;
  } catch (error) {
    return '/';
  }
}

function parsePush(text, origin) {
  var data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (error) { data = { body: String(text || '') }; }
  var title = typeof data.title === 'string' && data.title.trim() ? data.title.trim().slice(0, 80) : 'Berber Randevu';
  var body = typeof data.body === 'string' ? data.body.trim().slice(0, 200) : 'Yeni bir bildirimin var.';
  var tag = typeof data.tag === 'string' ? data.tag.replace(/[^A-Z0-9_]/gi, '').slice(0, 64) : 'general';
  return { title: title, body: body, url: safeUrl(data.url, origin), tag: tag };
}

async function trimCache(name, max) {
  var cache = await caches.open(name);
  var keys = await cache.keys();
  for (var index = 0; index < keys.length - max; index++) await cache.delete(keys[index]);
}

async function navigationResponse(request) {
  var cache = await caches.open(SHELL_CACHE);
  try {
    var response = await fetch(request);
    // SPA'da her rota aynı index.html'i döndürür; kişiye özel veri içermez. Yalnızca başarılı HTML kabuğu saklanır.
    var type = (response.headers.get('content-type') || '');
    if (shouldStore(response) && type.indexOf('text/html') !== -1) await cache.put(APP_SHELL_KEY, response.clone());
    return response;
  } catch (error) {
    return (await cache.match(APP_SHELL_KEY)) || (await cache.match(OFFLINE_URL)) || Response.error();
  }
}

async function assetResponse(request) {
  var cache = await caches.open(ASSET_CACHE);
  var cached = await cache.match(request);
  if (cached) return cached;
  var response = await fetch(request);
  if (shouldStore(response)) {
    await cache.put(request, response.clone());
    trimCache(ASSET_CACHE, MAX_ASSET_ENTRIES);
  }
  return response;
}

async function staticResponse(request) {
  var cache = await caches.open(SHELL_CACHE);
  var cached = await cache.match(request);
  var network = fetch(request).then(function (response) {
    if (shouldStore(response)) cache.put(request, response.clone());
    return response;
  }).catch(function () { return cached || Response.error(); });
  return cached || network;
}

self.addEventListener('install', function (event) {
  event.waitUntil(caches.open(SHELL_CACHE).then(function (cache) { return cache.addAll(PRECACHE); }));
});

self.addEventListener('activate', function (event) {
  event.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (key) {
      return key.startsWith('berber-') && key !== SHELL_CACHE && key !== ASSET_CACHE;
    }).map(function (key) { return caches.delete(key); }));
  }).then(function () { return self.clients.claim(); }));
});

self.addEventListener('fetch', function (event) {
  var policy = cachePolicy(event.request, self.location.origin);
  if (policy === 'bypass' || policy === 'network') return;
  if (policy === 'navigation') event.respondWith(navigationResponse(event.request));
  else if (policy === 'asset') event.respondWith(assetResponse(event.request));
  else if (policy === 'static') event.respondWith(staticResponse(event.request));
});

self.addEventListener('message', function (event) {
  if (event.data && event.data.type === 'SKIP_WAITING') self.skipWaiting();
  if (event.data && event.data.type === 'CLEAR_PRIVATE_STATE') {
    // Çıkışta yalnızca kabuk yeniden alınır; özel veri zaten önbellekte tutulmaz.
    event.waitUntil(caches.open(SHELL_CACHE).then(function (cache) { return cache.delete(APP_SHELL_KEY); }));
  }
});

self.addEventListener('push', function (event) {
  var payload = parsePush(event.data ? event.data.text() : '', self.location.origin);
  event.waitUntil(self.registration.showNotification(payload.title, {
    body: payload.body,
    icon: '/icons/icon-192.png',
    badge: '/icons/badge-96.png',
    tag: payload.tag,
    lang: 'tr',
    data: { url: payload.url }
  }));
});

self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  var target = safeUrl(event.notification.data && event.notification.data.url, self.location.origin);
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (windows) {
    for (var index = 0; index < windows.length; index++) {
      var client = windows[index];
      if (new URL(client.url).origin === self.location.origin && 'focus' in client) {
        return client.focus().then(function (focused) { return focused && 'navigate' in focused ? focused.navigate(target) : focused; });
      }
    }
    return self.clients.openWindow(target);
  }));
});

self.addEventListener('pushsubscriptionchange', function (event) {
  // Service worker oturum token'ı taşımaz; açık sekmeler aboneliği oturumla yeniden eşitler.
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (windows) {
    windows.forEach(function (client) { client.postMessage({ type: 'PUSH_SUBSCRIPTION_CHANGED' }); });
  }));
});
