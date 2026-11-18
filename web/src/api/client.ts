import axios, { AxiosError, CanceledError, type InternalAxiosRequestConfig } from 'axios';
import type { AuthData } from './types';

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';
const REFRESH_KEY = 'saas.refreshToken';
let accessToken: string | null = null;
let sessionController = new AbortController();
let refreshPromise: Promise<AuthData | null> | null = null;
const sessionListeners = new Set<(data: AuthData | null) => void>();

const config = { baseURL: API_URL, headers: { 'Content-Type': 'application/json' }, timeout: 15_000 };
export const api = axios.create(config);
// Auth requests must not recursively trigger the protected API's 401 interceptor.
const authApi = axios.create(config);

export function getRefreshToken() { return localStorage.getItem(REFRESH_KEY); }
function applyTokens(data: AuthData) {
  accessToken = data.accessToken;
  localStorage.setItem(REFRESH_KEY, data.refreshToken);
}
export function subscribeSession(listener: (data: AuthData | null) => void) {
  sessionListeners.add(listener);
  return () => { sessionListeners.delete(listener); };
}
export function resetSessionTransport() {
  sessionController.abort();
  sessionController = new AbortController();
  refreshPromise = null;
  accessToken = null;
  localStorage.removeItem(REFRESH_KEY);
}

export function restoreSession(): Promise<AuthData | null> {
  if (refreshPromise) return refreshPromise;
  const refreshToken = getRefreshToken();
  if (!refreshToken) return Promise.resolve(null);
  const controller = sessionController;
  const pending = authApi.post<AuthData>('/api/auth/refresh', { refreshToken }, { signal: controller.signal })
    .then(({ data }) => {
      if (controller.signal.aborted) return null;
      applyTokens(data);
      sessionListeners.forEach(listener => listener(data));
      return data;
    })
    .catch(() => {
      if (!controller.signal.aborted) {
        resetSessionTransport();
        sessionListeners.forEach(listener => listener(null));
      }
      return null;
    })
    .finally(() => { if (refreshPromise === pending) refreshPromise = null; });
  refreshPromise = pending;
  return pending;
}

export async function authenticate(path: '/api/auth/login' | '/api/auth/register', values: object) {
  const controller = sessionController;
  const { data } = await authApi.post<AuthData>(path, values, { signal: controller.signal });
  if (controller.signal.aborted) throw new CanceledError();
  applyTokens(data);
  return data;
}

/** Çok işletmeli kullanıcı: oturum açıkken başka işletmeye geç (yeni token çifti). */
export async function switchTenantRequest(tenantId: string) {
  const { data } = await api.post<AuthData>('/api/me/switch-tenant', { tenantId });
  applyTokens(data);
  return data;
}

const MAX_AUTO_RETRY_SECONDS = 10;
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

api.interceptors.request.use((request: InternalAxiosRequestConfig) => {
  const requestSignal = request.signal as AbortSignal | undefined;
  request.signal = requestSignal
    ? AbortSignal.any([requestSignal, sessionController.signal])
    : sessionController.signal;
  if (accessToken) request.headers.Authorization = `Bearer ${accessToken}`;
  return request;
}, undefined, { synchronous: true });

api.interceptors.response.use(response => response, async (error: AxiosError) => {
  const body = error.response?.data as { code?: string; message?: string } | undefined;
  if (body?.code === 'QUOTA_EXCEEDED' || error.response?.status === 429) {
    window.dispatchEvent(new CustomEvent('saas:api-error', { detail: {
      quota: body?.code === 'QUOTA_EXCEEDED',
      message: body?.code === 'QUOTA_EXCEEDED' ? body.message : `Çok fazla istek gönderdiniz. ${error.response?.headers?.['retry-after'] ?? 60} saniye bekleyip tekrar deneyin.`,
    } }));
  }
  const original = error.config as (InternalAxiosRequestConfig & { _retry?: boolean; _rateRetry?: boolean }) | undefined;
  // 429: yalnızca okuma (GET) kısa Retry-After ile bir kez otomatik yeniden denenir; yazma istekleri asla.
  if (error.response?.status === 429 && original && !original._rateRetry && original.method?.toLowerCase() === 'get' && !original.signal?.aborted) {
    const wait = Number(error.response.headers?.['retry-after']);
    if (Number.isFinite(wait) && wait > 0 && wait <= MAX_AUTO_RETRY_SECONDS) {
      original._rateRetry = true;
      await sleep(wait * 1000);
      if (!original.signal?.aborted) return api(original);
    }
  }
  if (error.response?.status !== 401 || !original || original._retry || original.signal?.aborted) throw error;
  original._retry = true;
  const data = await restoreSession();
  if (!data || original.signal?.aborted) throw error;
  original.headers.Authorization = `Bearer ${data.accessToken}`;
  return api(original);
});
