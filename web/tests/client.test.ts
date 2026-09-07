import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { beforeAll, beforeEach, expect, test, vi } from 'vitest';

const adapter = vi.fn();
let client: typeof import('../src/api/client');
const session = { accessToken: 'new-access', refreshToken: 'new-refresh', memberships: [] };
function response(config: InternalAxiosRequestConfig, data: unknown, status = 200) {
  return { config, data, status, statusText: 'OK', headers: {} };
}

beforeAll(async () => {
  axios.defaults.adapter = adapter;
  client = await import('../src/api/client');
});
beforeEach(() => { client.resetSessionTransport(); adapter.mockReset(); });

test('concurrent 401 responses share one refresh and retry with the new token', async () => {
  localStorage.setItem('saas.refreshToken', 'old-refresh');
  let release!: () => void;
  const barrier = new Promise<void>(resolve => { release = resolve; });
  adapter.mockImplementation(async (config: InternalAxiosRequestConfig) => {
    if (config.url === '/api/auth/refresh') { await barrier; return response(config, session); }
    if (config.headers.Authorization !== 'Bearer new-access') {
      throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, response(config, {}, 401));
    }
    return response(config, []);
  });
  const first = client.api.get('/api/services');
  const second = client.api.get('/api/staff');
  await vi.waitFor(() => expect(adapter.mock.calls.filter(([config]) => config.url === '/api/auth/refresh')).toHaveLength(1));
  release();
  await expect(Promise.all([first, second])).resolves.toHaveLength(2);
  expect(adapter.mock.calls.filter(([config]) => config.url === '/api/auth/refresh')).toHaveLength(1);
  expect(localStorage.getItem('saas.refreshToken')).toBe('new-refresh');
});

test('logout aborts a pending refresh and its late response cannot restore tokens', async () => {
  localStorage.setItem('saas.refreshToken', 'old-refresh');
  let release!: () => void;
  const barrier = new Promise<void>(resolve => { release = resolve; });
  adapter.mockImplementation(async (config: InternalAxiosRequestConfig) => { await barrier; return response(config, session); });
  const pending = client.restoreSession();
  await vi.waitFor(() => expect(adapter).toHaveBeenCalledOnce());
  const signal = adapter.mock.calls[0][0].signal;
  client.resetSessionTransport();
  expect(signal.aborted).toBe(true);
  release();
  await expect(pending).resolves.toBeNull();
  expect(localStorage.getItem('saas.refreshToken')).toBeNull();
});

test('logout aborts pending mutations as well as reads', async () => {
  let release!: () => void;
  const barrier = new Promise<void>(resolve => { release = resolve; });
  adapter.mockImplementation(async (config: InternalAxiosRequestConfig) => { await barrier; return response(config, {}); });
  const pending = client.api.post('/api/services', { name: 'Kesim' });
  const rejected = expect(pending).rejects.toMatchObject({ code: 'ERR_CANCELED' });
  client.resetSessionTransport();
  expect(adapter.mock.calls[0][0].signal.aborted).toBe(true);
  release();
  await rejected;
});

test('invalid refresh expires the session and does not recursively refresh', async () => {
  localStorage.setItem('saas.refreshToken', 'expired');
  const listener = vi.fn();
  const unsubscribe = client.subscribeSession(listener);
  adapter.mockRejectedValue(new Error('Invalid refresh'));
  await expect(client.restoreSession()).resolves.toBeNull();
  expect(adapter).toHaveBeenCalledOnce();
  expect(listener).toHaveBeenCalledWith(null);
  expect(localStorage.getItem('saas.refreshToken')).toBeNull();
  unsubscribe();
});
