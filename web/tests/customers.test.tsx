import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { AppRoutes } from '../src/router/AppRoutes';
import { AuthProvider } from '../src/store/auth';
import { createQueryClient } from '../src/query/client';
import { api, restoreSession } from '../src/api/client';

vi.mock('../src/api/client', () => ({
  api: { get: vi.fn(), post: vi.fn(), patch: vi.fn() },
  restoreSession: vi.fn(), subscribeSession: () => () => {}, resetSessionTransport: vi.fn(), authenticate: vi.fn(),
}));

const customer = {
  customerId: 'customer-1', customerName: 'Ada Müşteri', customerEmail: 'ada@example.com', customerPhone: '+905550000000',
  trustState: 'NEW', completedCount: 1, noShowCount: 0, blockedReason: null, trustedAt: null, trustReason: null,
  evidenceAppointmentId: null, version: 3, lastAppointmentAt: '2030-01-07T09:00:00Z',
};

beforeEach(() => {
  vi.mocked(restoreSession).mockResolvedValue({ accessToken: 'token', refreshToken: 'refresh', memberships: [{ tenantId: 'tenant-1', tenantName: 'Moda Berber', role: 'OWNER' }] });
  vi.mocked(api.get).mockImplementation(async url => {
    if (url === '/api/me') return { data: { userId: 'owner-1', tenantId: 'tenant-1', role: 'OWNER' } };
    if (url === '/api/booking-customers') return { data: [customer] };
    if (url === '/api/booking-customers/customer-1/trust-events') return { data: [] };
    throw new Error(`Unexpected GET ${url}`);
  });
  vi.mocked(api.patch).mockResolvedValue({ data: {
    customerId: 'customer-1', trustState: 'TRUSTED', completedCount: 1, noShowCount: 0,
    blockedReason: null, trustedAt: '2030-01-07T10:00:00Z', trustReason: 'Kasada kimliği doğrulandı',
    evidenceAppointmentId: null, version: 4, updatedAt: '2030-01-07T10:00:00Z',
  } });
});
afterEach(cleanup);

test('owner reviews a customer and records a versioned trust decision with a reason', async () => {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(<QueryClientProvider client={client}><AuthProvider><MemoryRouter initialEntries={['/app/customers']}><AppRoutes /></MemoryRouter></AuthProvider></QueryClientProvider>);

  await screen.findByRole('heading', { name: 'Müşteriler' });
  const customerName = await screen.findByText('Ada Müşteri');
  fireEvent.click(customerName.closest('button')!);
  expect(await screen.findByRole('heading', { name: 'Ada Müşteri' })).toBeTruthy();
  const save = screen.getByRole('button', { name: 'Kararı kaydet' });
  expect((save as HTMLButtonElement).disabled).toBe(true);
  fireEvent.change(screen.getByLabelText('Gerekçe'), { target: { value: 'Kasada kimliği doğrulandı' } });
  fireEvent.click(save);

  await waitFor(() => expect(api.patch).toHaveBeenCalledWith('/api/booking-customers/customer-1/trust', {
    trustState: 'TRUSTED', expectedVersion: 3, reason: 'Kasada kimliği doğrulandı',
  }));
  expect(await screen.findByText('Ada Müşteri için güven durumu güncellendi.')).toBeTruthy();
});
