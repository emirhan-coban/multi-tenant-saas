import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';
const REFRESH_KEY = 'saas.refreshToken';
let accessToken: string | null = null;
let refreshPromise: Promise<string | null> | null = null;

export const api = axios.create({ baseURL: API_URL, headers: { 'Content-Type': 'application/json' } });

export function setAccessToken(token: string | null) { accessToken = token; }
export function getRefreshToken() { return localStorage.getItem(REFRESH_KEY); }
export function persistRefreshToken(token: string | null) {
  if (token) localStorage.setItem(REFRESH_KEY, token); else localStorage.removeItem(REFRESH_KEY);
}

async function refreshAccessToken() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return null;
  if (!refreshPromise) {
    refreshPromise = api.post('/api/auth/refresh', { refreshToken })
      .then(({ data }) => { setAccessToken(data.accessToken); persistRefreshToken(data.refreshToken); return data.accessToken; })
      .catch(() => { setAccessToken(null); persistRefreshToken(null); return null; })
      .finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`;
  return config;
});

api.interceptors.response.use(response => response, async (error: AxiosError) => {
  const original = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined;
  if (error.response?.status !== 401 || !original || original._retry || original.url?.includes('/api/auth/refresh')) throw error;
  original._retry = true;
  const token = await refreshAccessToken();
  if (!token) throw error;
  original.headers.Authorization = `Bearer ${token}`;
  return api(original);
});