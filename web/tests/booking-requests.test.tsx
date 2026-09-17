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

const row = {
  id: 'request-1', customerId: 'customer-1', customerName: 'Ada Müşteri', customerEmail: 'ada@example.com', customerPhone: '+905550000000',
  trustState: 'NEW', completedCount: 0, noShowCount: 0, trustVersion: 0, serviceId: 'service-1', serviceName: 'Saç Kesimi', staffId: 'staff-1', staffName: 'Deniz Usta',
  startsAt: '2030-01-07T09:00:00Z', busyUntil: '2030-01-07T09:40:00Z', status: 'PENDING_APPROVAL', decision: 'REQUIRE_APPROVAL',
  holdExpiresAt: '2030-01-07T08:15:00Z', appointmentId: null, note: 'Yanları kısa olsun', rejectionReason: null, createdAt: '2030-01-01T00:00:00Z', decidedAt: null,
};
let approved = false;

beforeEach(() => {
  approved = false;
  vi.mocked(restoreSession).mockResolvedValue({ accessToken: 'token', refreshToken: 'refresh', memberships: [{ tenantId: 'tenant-1', tenantName: 'Moda Berber', role: 'OWNER' }] });
  vi.mocked(api.get).mockImplementation(async url => ({ data: url === '/api/me' ? { userId: 'owner-1', tenantId: 'tenant-1', role: 'OWNER' } : url === '/api/reschedule-requests' ? [] : approved ? [] : [row] }));
  vi.mocked(api.post).mockImplementation(async () => { approved = true; return { data: { ...row, status: 'APPROVED', holdExpiresAt: null, appointmentId: 'appointment-1' } }; });
});
afterEach(cleanup);

test('owner reviews customer context and approves a first booking request', async () => {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(<QueryClientProvider client={client}><AuthProvider><MemoryRouter initialEntries={['/app/booking-requests']}><AppRoutes /></MemoryRouter></AuthProvider></QueryClientProvider>);
  await screen.findByRole('heading', { name: 'Onay bekleyenler' });
  expect(await screen.findByText('Ada Müşteri')).toBeTruthy();
  expect(screen.getAllByText('İlk ziyaret').length).toBeGreaterThan(0);
  expect(screen.getByText((_value, element) => element?.classList.contains('customer-history') === true)).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Onayla' }));
  await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/booking-requests/request-1/approve', {}, expect.objectContaining({ headers: expect.any(Object) })));
  await screen.findByText('Ada Müşteri için randevu onaylandı.');
  expect(screen.getByRole('heading', { name: 'Bekleyen talep yok' })).toBeTruthy();
});
