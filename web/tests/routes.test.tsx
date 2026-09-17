import { StrictMode } from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router';
import { AuthProvider } from '../src/store/auth';
import { AppRoutes } from '../src/router/AppRoutes';
import { createQueryClient } from '../src/query/client';
import { api, restoreSession } from '../src/api/client';

vi.mock('../src/api/client', () => ({
  api: { get: vi.fn() },
  restoreSession: vi.fn(),
  subscribeSession: () => () => {},
  resetSessionTransport: vi.fn(),
  authenticate: vi.fn(),
}));
const authData = {
  accessToken: 'token', refreshToken: 'refresh', memberships: [
    { tenantId: 'wrong-first', tenantName: 'Diğer işletme', role: 'OWNER' as const },
    { tenantId: 'active', tenantName: 'Aktif işletme', role: 'STAFF' as const },
  ],
};
function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }
function mount(path = '/app') {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(<StrictMode><QueryClientProvider client={client}><AuthProvider><MemoryRouter initialEntries={[path]}>
    <AppRoutes /><Location />
  </MemoryRouter></AuthProvider></QueryClientProvider></StrictMode>);
  return client;
}
beforeEach(() => { vi.mocked(api.get).mockReset(); vi.mocked(restoreSession).mockReset(); });
afterEach(cleanup);

test('an unauthenticated direct visit redirects to login without requesting tenant data', async () => {
  vi.mocked(restoreSession).mockResolvedValue(null);
  mount();
  await screen.findByRole('heading', { name: 'Hesabınıza giriş yapın' });
  expect(screen.getByTestId('location').textContent).toBe('/login');
  expect(api.get).not.toHaveBeenCalled();
});

test('dashboard uses the verified tenant rather than the first membership; logout clears cache', async () => {
  vi.mocked(restoreSession).mockResolvedValue(authData);
  vi.mocked(api.get).mockResolvedValue({ data: { userId: 'user', tenantId: 'active', role: 'STAFF' } });
  const client = mount();
  await screen.findByRole('heading', { level: 1, name: 'Aktif işletme' });
  expect(screen.queryByText('Diğer işletme')).toBeNull();
  client.setQueryData(['tenant', 'active', 'services'], ['Private data']);
  fireEvent.click(screen.getByRole('button', { name: 'Oturumu kapat' }));
  await screen.findByRole('heading', { name: 'Hesabınıza giriş yapın' });
  expect(client.getQueryCache().getAll()).toHaveLength(0);
});

test('profile failure offers retry and never exposes the dashboard before verification', async () => {
  vi.mocked(restoreSession).mockResolvedValue(authData);
  vi.mocked(api.get).mockRejectedValueOnce(new Error('Offline'));
  mount();
  await screen.findByRole('heading', { name: 'Çalışma alanı yüklenemedi' });
  expect(screen.queryByRole('heading', { name: 'Aktif işletme' })).toBeNull();
  vi.mocked(api.get).mockResolvedValue({ data: { userId: 'user', tenantId: 'active', role: 'STAFF' } });
  fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }));
  await screen.findByRole('heading', { level: 1, name: 'Aktif işletme' });
});

test('unknown URLs show a recoverable 404', async () => {
  vi.mocked(restoreSession).mockResolvedValue(null);
  mount('/missing');
  await waitFor(() => expect(screen.getByRole('link', { name: 'Çalışma alanına dön' }).getAttribute('href')).toBe('/app'));
});

for (const role of ['OWNER', 'MANAGER', 'STAFF'] as const) {
  test(`${role} sees only the permitted navigation items`, async () => {
    vi.mocked(restoreSession).mockResolvedValue(authData);
    vi.mocked(api.get).mockImplementation(async path => ({ data: path === '/api/usage'
      ? { plan: 'FREE', staff: { used: 1, limit: 2 }, appointments: { used: 0, limit: 100 }, resetsAt: '2030-02-01T00:00:00Z', timezone: 'Europe/Istanbul', requestsPerMinute: 60 }
      : { userId: 'user', tenantId: 'active', role } }));
    mount();
    const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(nav).getAllByRole('link')).toHaveLength(role === 'STAFF' ? 4 : 15);
    expect(within(nav).getByRole('link', { name: 'Genel Bakış' }).getAttribute('aria-current')).toBe('page');
    if (role === 'STAFF') expect(within(nav).queryByRole('link', { name: 'Hizmetler' })).toBeNull();
    fireEvent.click(within(nav).getByRole('link', { name: 'Hesabım' }));
    await screen.findByRole('heading', { name: 'Hesap bilgileriniz' });
    expect(within(nav).getByRole('link', { name: 'Genel Bakış' }).getAttribute('aria-current')).toBeNull();
    expect(within(nav).getByRole('link', { name: 'Hesabım' }).getAttribute('aria-current')).toBe('page');
    expect(document.activeElement?.id).toBe('panel-content');
  });
}
for (const path of ['services', 'staff', 'working-hours', 'appointments', 'booking-requests', 'booking-settings', 'shop-design', 'customers', 'reports', 'audit-log', 'plan']) {
  test(`STAFF cannot open /app/${path} directly`, async () => {
    vi.mocked(restoreSession).mockResolvedValue(authData);
    vi.mocked(api.get).mockResolvedValue({ data: { userId: 'user', tenantId: 'active', role: 'STAFF' } });
    mount(`/app/${path}`);
    await screen.findByRole('heading', { name: 'Bu sayfaya erişiminiz yok' });
    expect(screen.queryByText('Bu alan hazırlanıyor')).toBeNull();
    expect(vi.mocked(api.get).mock.calls.every(([url]) => url === '/api/me')).toBe(true);
    fireEvent.click(screen.getByRole('link', { name: 'Genel bakışa dön' }));
    await screen.findByRole('heading', { level: 1, name: 'Aktif işletme' });
  });
}
test('OWNER can open management deep links', async () => {
  vi.mocked(restoreSession).mockResolvedValue(authData);
  vi.mocked(api.get).mockImplementation(async url => ({ data: url === '/api/me' ? { userId: 'user', tenantId: 'active', role: 'OWNER' } : [] }));
  mount('/app/services');
  await screen.findByRole('heading', { name: 'Hizmetler' });
  expect(screen.queryByRole('heading', { name: 'Bu sayfaya erişiminiz yok' })).toBeNull();
});
test('unrecognized roles have no access to panel or navigation', async () => {
  vi.mocked(restoreSession).mockResolvedValue(authData);
  vi.mocked(api.get).mockResolvedValue({ data: { userId: 'user', tenantId: 'active', role: 'UNKNOWN' } });
  mount();
  await screen.findByRole('heading', { name: 'Bu sayfaya erişiminiz yok' });
  expect(screen.queryByRole('navigation')).toBeNull();
  expect(screen.getByRole('button', { name: 'Oturumu kapat' })).toBeTruthy();
});
