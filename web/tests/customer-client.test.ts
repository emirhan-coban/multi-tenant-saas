import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { beforeAll, beforeEach, expect, test, vi } from 'vitest';

const adapter = vi.fn();
let client: typeof import('../src/api/customer-client');
const session = {
  accessToken: 'customer-access',
  refreshToken: 'customer-refresh',
  customer: { id: 'customer-1', email: 'ada@example.com', fullName: 'Ada', phone: null },
};

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200) {
  return { config, data, status, statusText: 'OK', headers: {} };
}

beforeAll(async () => {
  axios.defaults.adapter = adapter;
  client = await import('../src/api/customer-client');
});

beforeEach(() => {
  client.resetCustomerSession();
  adapter.mockReset();
  localStorage.setItem('saas.refreshToken', 'staff-refresh');
});

test('customer login uses a separate refresh key and attaches only customer access token', async () => {
  adapter.mockImplementation(async (config: InternalAxiosRequestConfig) => response(config, session));
  await client.authenticateCustomer('login', { email: 'ada@example.com', password: 'secret123' });
  await client.customerApi.get('/api/customer/v1/me');
  expect(localStorage.getItem('saas.customer.refreshToken')).toBe('customer-refresh');
  expect(localStorage.getItem('saas.refreshToken')).toBe('staff-refresh');
  expect(adapter.mock.calls[1][0].headers.Authorization).toBe('Bearer customer-access');
});

test('concurrent customer 401 responses share one refresh request', async () => {
  localStorage.setItem('saas.customer.refreshToken', 'old-customer-refresh');
  let release!: () => void;
  const barrier = new Promise<void>(resolve => { release = resolve; });
  adapter.mockImplementation(async (config: InternalAxiosRequestConfig) => {
    if (config.url === '/api/customer/v1/auth/refresh') {
      await barrier;
      return response(config, session);
    }
    if (config.headers.Authorization !== 'Bearer customer-access') {
      throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, response(config, {}, 401));
    }
    return response(config, {});
  });
  const first = client.customerApi.get('/api/customer/v1/me');
  const second = client.customerApi.get('/api/customer/v1/booking-requests/1');
  await vi.waitFor(() => expect(adapter.mock.calls.filter(([config]) => config.url === '/api/customer/v1/auth/refresh')).toHaveLength(1));
  release();
  await expect(Promise.all([first, second])).resolves.toHaveLength(2);
});

test('logout aborts pending refresh and cannot erase the staff session', async () => {
  localStorage.setItem('saas.customer.refreshToken', 'old-customer-refresh');
  let release!: () => void;
  const barrier = new Promise<void>(resolve => { release = resolve; });
  adapter.mockImplementation(async (config: InternalAxiosRequestConfig) => {
    await barrier;
    return response(config, session);
  });
  const pending = client.restoreCustomerSession();
  await vi.waitFor(() => expect(adapter).toHaveBeenCalledOnce());
  const signal = adapter.mock.calls[0][0].signal;
  client.resetCustomerSession();
  expect(signal.aborted).toBe(true);
  release();
  await expect(pending).resolves.toBeNull();
  expect(localStorage.getItem('saas.customer.refreshToken')).toBeNull();
  expect(localStorage.getItem('saas.refreshToken')).toBe('staff-refresh');
});
