import axios, { AxiosError, CanceledError, type InternalAxiosRequestConfig } from 'axios';

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';
const REFRESH_KEY = 'saas.customer.refreshToken';
const config = { baseURL: API_URL, headers: { 'Content-Type': 'application/json' }, timeout: 15_000 };

export type Customer = { id: string; email: string; fullName: string; phone: string | null };
export type CustomerAuthData = { accessToken: string; refreshToken: string; customer: Customer };

export const customerApi = axios.create(config);
const customerAuthApi = axios.create(config);
let accessToken: string | null = null;
let refreshPromise: Promise<CustomerAuthData | null> | null = null;
let sessionController = new AbortController();
const listeners = new Set<(data: CustomerAuthData | null) => void>();

function apply(data: CustomerAuthData) {
  accessToken = data.accessToken;
  localStorage.setItem(REFRESH_KEY, data.refreshToken);
}

export function subscribeCustomerSession(listener: (data: CustomerAuthData | null) => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function resetCustomerSession() {
  sessionController.abort();
  sessionController = new AbortController();
  accessToken = null;
  refreshPromise = null;
  localStorage.removeItem(REFRESH_KEY);
}

export function restoreCustomerSession(): Promise<CustomerAuthData | null> {
  if (refreshPromise) return refreshPromise;
  const refreshToken = localStorage.getItem(REFRESH_KEY);
  if (!refreshToken) return Promise.resolve(null);
  const controller = sessionController;
  const pending = customerAuthApi.post<CustomerAuthData>('/api/customer/v1/auth/refresh', { refreshToken }, { signal: controller.signal })
    .then(({ data }) => {
      if (controller.signal.aborted) return null;
      apply(data);
      listeners.forEach(listener => listener(data));
      return data;
    })
    .catch(() => {
      if (!controller.signal.aborted) {
        resetCustomerSession();
        listeners.forEach(listener => listener(null));
      }
      return null;
    })
    .finally(() => { if (refreshPromise === pending) refreshPromise = null; });
  refreshPromise = pending;
  return pending;
}

export async function authenticateCustomer(mode: 'login' | 'register', values: object) {
  resetCustomerSession();
  const controller = sessionController;
  const { data } = await customerAuthApi.post<CustomerAuthData>(`/api/customer/v1/auth/${mode}`, values, { signal: controller.signal });
  if (controller.signal.aborted) throw new CanceledError();
  apply(data);
  return data;
}

customerApi.interceptors.request.use((request: InternalAxiosRequestConfig) => {
  const requestSignal = request.signal as AbortSignal | undefined;
  request.signal = requestSignal
    ? AbortSignal.any([requestSignal, sessionController.signal])
    : sessionController.signal;
  if (accessToken) request.headers.Authorization = `Bearer ${accessToken}`;
  return request;
}, undefined, { synchronous: true });

customerApi.interceptors.response.use(response => response, async (error: AxiosError) => {
  const original = error.config as (InternalAxiosRequestConfig & { _customerRetry?: boolean }) | undefined;
  if (error.response?.status !== 401 || !original || original._customerRetry || original.signal?.aborted) throw error;
  original._customerRetry = true;
  const data = await restoreCustomerSession();
  if (!data || original.signal?.aborted) throw error;
  original.headers.Authorization = `Bearer ${data.accessToken}`;
  return customerApi(original);
});
