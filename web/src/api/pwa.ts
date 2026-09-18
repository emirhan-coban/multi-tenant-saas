import axios from 'axios';
import { api } from './client';
import { customerApi } from './customer-client';

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';
const publicApi = axios.create({ baseURL: API_URL, timeout: 10_000 });

export type PushAudience = 'customer' | 'staff';
export type PushConfig = { enabled: boolean; publicKey?: string };
export type SubscriptionPayload = { endpoint: string; keys: { p256dh: string; auth: string } };

const paths: Record<PushAudience, string> = {
  customer: '/api/customer/v1/push-subscriptions',
  staff: '/api/push-subscriptions',
};

function client(audience: PushAudience) {
  return audience === 'customer' ? customerApi : api;
}

export async function getPushConfig(signal?: AbortSignal) {
  return (await publicApi.get<PushConfig>('/api/public/v1/push/config', { signal })).data;
}

export async function registerPushSubscription(audience: PushAudience, subscription: SubscriptionPayload) {
  await client(audience).post(paths[audience], subscription);
}

export async function unregisterPushSubscription(audience: PushAudience, endpoint: string) {
  await client(audience).post(`${paths[audience]}/unsubscribe`, { endpoint });
}

export async function sendTestPush(audience: PushAudience) {
  return (await client(audience).post<{ queued: number }>(`${paths[audience]}/test`, {})).data;
}

/** Tüm cihazlardaki oturumları ve push aboneliklerini sonlandırır. */
export async function logoutEverywhere(audience: PushAudience) {
  await client(audience).post(audience === 'customer' ? '/api/customer/v1/auth/logout-all' : '/api/auth/logout-all', {});
}
