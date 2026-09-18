import { captureInstallPrompt } from './install';

export const PUSH_CHANGED_EVENT = 'pwa:push-subscription-changed';
export const UPDATE_READY_EVENT = 'pwa:update-ready';

/** Service worker yalnızca üretim derlemesinde kaydedilir; geliştirmede eski önbellek karışıklığı yaşanmaz. */
export function registerPwa() {
  captureInstallPrompt();
  if (!import.meta.env.PROD || typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return;
  navigator.serviceWorker.addEventListener('message', event => {
    if (event.data?.type === 'PUSH_SUBSCRIPTION_CHANGED') window.dispatchEvent(new Event(PUSH_CHANGED_EVENT));
  });
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).then(registration => {
      registration.addEventListener('updatefound', () => {
        const worker = registration.installing;
        worker?.addEventListener('statechange', () => {
          if (worker.state === 'installed' && navigator.serviceWorker.controller)
            window.dispatchEvent(new CustomEvent(UPDATE_READY_EVENT, { detail: worker }));
        });
      });
    }).catch(() => { /* PWA opsiyoneldir; uygulama service worker olmadan tam çalışır. */ });
  });
}

export function applyUpdate(worker: ServiceWorker) {
  navigator.serviceWorker.addEventListener('controllerchange', () => window.location.reload(), { once: true });
  worker.postMessage({ type: 'SKIP_WAITING' });
}
