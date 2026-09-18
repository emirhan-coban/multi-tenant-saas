import axios from 'axios';
import { getPushConfig, registerPushSubscription, unregisterPushSubscription, type PushAudience, type SubscriptionPayload } from '../api/pwa';

export type PushState = 'unsupported' | 'needs-install' | 'unavailable' | 'denied' | 'prompt' | 'subscribed';

export function isStandalone() {
  if (typeof window === 'undefined') return false;
  const nav = navigator as Navigator & { standalone?: boolean };
  return nav.standalone === true || (typeof window.matchMedia === 'function' && window.matchMedia('(display-mode: standalone)').matches);
}

export function isIos() {
  if (typeof navigator === 'undefined') return false;
  return /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

export function pushSupported() {
  return typeof window !== 'undefined' && window.isSecureContext !== false
    && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

/** VAPID public key (base64url) → Uint8Array; tarayıcı `applicationServerKey` olarak bunu bekler. */
export function urlBase64ToUint8Array(value: string) {
  const padding = '='.repeat((4 - (value.length % 4)) % 4);
  const base64 = (value + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(base64);
  const output = new Uint8Array(raw.length);
  for (let index = 0; index < raw.length; index++) output[index] = raw.charCodeAt(index);
  return output;
}

export function toPayload(subscription: PushSubscription): SubscriptionPayload {
  const json = subscription.toJSON();
  if (!json.endpoint || !json.keys?.p256dh || !json.keys?.auth) throw new Error('Eksik push aboneliği');
  return { endpoint: json.endpoint, keys: { p256dh: json.keys.p256dh, auth: json.keys.auth } };
}

async function registration(timeoutMs = 4000) {
  return await Promise.race([
    navigator.serviceWorker.ready,
    new Promise<never>((_, reject) => window.setTimeout(() => reject(new Error('Service worker hazır değil')), timeoutMs)),
  ]);
}

export async function currentSubscription() {
  if (!pushSupported()) return null;
  try { return await (await registration()).pushManager.getSubscription(); }
  catch { return null; }
}

export async function detectPushState(): Promise<PushState> {
  if (!pushSupported()) return isIos() && !isStandalone() ? 'needs-install' : 'unsupported';
  if (Notification.permission === 'denied') return 'denied';
  const subscription = await currentSubscription();
  if (Notification.permission === 'granted' && subscription) return 'subscribed';
  return 'prompt';
}

export class PushError extends Error {
  constructor(public readonly code: 'denied' | 'unavailable' | 'feature-disabled' | 'server' | 'dismissed', message: string) {
    super(message);
  }
}

/** Mutlaka bir kullanıcı tıklamasının içinden çağrılmalıdır; izin istemi yalnızca o zaman gösterilir. */
export async function enablePush(audience: PushAudience) {
  if (!pushSupported()) throw new PushError('unavailable', 'Bu tarayıcı anlık bildirimleri desteklemiyor.');
  const permission = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();
  if (permission === 'denied') throw new PushError('denied', 'Bildirim izni verilmedi.');
  if (permission !== 'granted') throw new PushError('dismissed', 'Bildirim izni isteği kapatıldı.');
  const config = await getPushConfig();
  if (!config.enabled || !config.publicKey) throw new PushError('unavailable', 'Anlık bildirimler sunucuda kapalı.');
  const worker = await registration();
  let subscription = await worker.pushManager.getSubscription();
  const expectedKey = urlBase64ToUint8Array(config.publicKey);
  if (subscription && !sameKey(subscription.options?.applicationServerKey ?? null, expectedKey)) {
    // Sunucu anahtarı değiştiyse eski abonelik artık kullanılamaz.
    await subscription.unsubscribe().catch(() => undefined);
    subscription = null;
  }
  subscription ??= await worker.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: expectedKey });
  try {
    await registerPushSubscription(audience, toPayload(subscription));
  } catch (error) {
    await subscription.unsubscribe().catch(() => undefined);
    if (axios.isAxiosError(error) && error.response?.data?.code === 'FEATURE_DISABLED')
      throw new PushError('feature-disabled', 'Anlık bildirimler bu işletmede şu anda kapalı.');
    throw new PushError('server', 'Bildirim aboneliği kaydedilemedi. Bağlantınızı kontrol edip tekrar deneyin.');
  }
  return subscription;
}

/** Bu cihazdaki aboneliği hem sunucudan hem tarayıcıdan kaldırır. Çıkışta da çağrılır; hata yutulur. */
export async function disablePush(audience: PushAudience) {
  const subscription = await currentSubscription();
  if (!subscription) return;
  await unregisterPushSubscription(audience, subscription.endpoint).catch(() => undefined);
  await subscription.unsubscribe().catch(() => undefined);
}

/** İzin verilmiş ve abonelik varsa sunucu kaydını tazeler (ör. yeniden giriş, pushsubscriptionchange). */
export async function syncPush(audience: PushAudience) {
  if (!pushSupported() || Notification.permission !== 'granted') return false;
  const subscription = await currentSubscription();
  if (!subscription) return false;
  await registerPushSubscription(audience, toPayload(subscription));
  return true;
}

function sameKey(current: ArrayBuffer | null, expected: Uint8Array) {
  if (!current) return false;
  const bytes = new Uint8Array(current);
  return bytes.length === expected.length && bytes.every((value, index) => value === expected[index]);
}
