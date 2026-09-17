import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { AppRoutes } from '../src/router/AppRoutes';
import { CustomerAuthProvider } from '../src/store/customer-auth';
import { cancelBookingRequest, createBookingRequest, getAvailability, getBookingRequest, getPublicScene, getPublicShop } from '../src/api/customer-booking';
import { restoreCustomerSession } from '../src/api/customer-client';

vi.mock('../src/components/BarberShopScene', () => ({
  BarberShopScene: () => <div data-testid="shop-scene">3D shop</div>,
}));

vi.mock('../src/api/customer-booking', () => ({
  getPublicShop: vi.fn(),
  getPublicScene: vi.fn(),
  getAvailability: vi.fn(),
  createBookingRequest: vi.fn(),
  getBookingRequest: vi.fn(),
  getCustomerBookings: vi.fn(),
  cancelBookingRequest: vi.fn(),
  createVisitCode: vi.fn(),
}));

vi.mock('../src/api/customer-client', () => ({
  customerApi: { get: vi.fn(), post: vi.fn() },
  authenticateCustomer: vi.fn(),
  resetCustomerSession: vi.fn(),
  restoreCustomerSession: vi.fn(),
  subscribeCustomerSession: () => () => {},
}));

const session = {
  accessToken: 'access', refreshToken: 'refresh',
  customer: { id: 'customer-1', email: 'ada@example.com', fullName: 'Ada Lovelace', phone: null },
};
const shop = {
  id: 'tenant-1', slug: 'moda', name: 'Moda Berber', timezone: 'Europe/Istanbul',
  services: [{ id: 'service-1', name: 'Saç Kesimi', durationMin: 30, bufferMin: 10, price: 450 }],
  staff: [{ id: 'staff-1', displayName: 'Deniz Usta', chairKey: 'window-chair' }],
};
const availability = {
  timezone: 'Europe/Istanbul', date: '2030-01-07',
  slots: [{ startsAt: '2030-01-07T09:00:00Z', endsAt: '2030-01-07T09:30:00Z' }],
};
const manifest = { version: 1, templateKey: 'CLASSIC_3_CHAIR' as const, accentColor: '#245e50', chairs: [
  { chairKey: 'chair-1', label: 'Pencere koltuğu', staffId: 'staff-1', staffName: 'Deniz Usta', positionX: 0, positionZ: -1.25, rotationY: 0 },
] };

beforeEach(() => {
  vi.mocked(restoreCustomerSession).mockResolvedValue(session);
  vi.mocked(getPublicShop).mockResolvedValue(shop);
  vi.mocked(getPublicScene).mockResolvedValue(manifest);
  vi.mocked(getAvailability).mockResolvedValue(availability);
  vi.mocked(createBookingRequest).mockResolvedValue({
    id: 'request-1', tenantSlug: 'moda', resultType: 'REQUEST', serviceId: 'service-1', staffId: 'staff-1',
    startsAt: availability.slots[0].startsAt, status: 'PENDING_APPROVAL', trustState: 'NEW',
    decision: 'REQUIRE_APPROVAL', holdExpiresAt: '2030-01-07T08:15:00Z', appointmentId: null,
    canCancel: true, cancellableUntil: '2030-01-07T08:15:00Z', version: 1, appointmentVersion: null, canReschedule: false, reschedulableUntil: null,
  });
  vi.mocked(getBookingRequest).mockResolvedValue({
    id: 'request-1', tenantSlug: 'moda', resultType: 'REQUEST', serviceId: 'service-1', staffId: 'staff-1',
    startsAt: availability.slots[0].startsAt, status: 'PENDING_APPROVAL', trustState: 'NEW',
    decision: 'REQUIRE_APPROVAL', holdExpiresAt: '2030-01-07T08:15:00Z', appointmentId: null,
    canCancel: true, cancellableUntil: '2030-01-07T08:15:00Z', version: 1, appointmentVersion: null, canReschedule: false, reschedulableUntil: null,
  });
  vi.mocked(cancelBookingRequest).mockResolvedValue({
    id: 'request-1', tenantSlug: 'moda', resultType: 'REQUEST', tenantName: 'Moda Berber', timezone: 'Europe/Istanbul',
    serviceId: 'service-1', serviceName: 'Saç Kesimi', staffId: 'staff-1', staffName: 'Deniz Usta',
    startsAt: availability.slots[0].startsAt, busyUntil: '2030-01-07T09:40:00Z', status: 'CANCELLED',
    appointmentStatus: null, trustState: 'NEW', decision: 'REQUIRE_APPROVAL', holdExpiresAt: null,
    appointmentId: null, canCancel: false, cancellableUntil: null, version: 2, appointmentVersion: null, canReschedule: false, reschedulableUntil: null, rejectionReason: null,
    createdAt: '2030-01-01T10:00:00Z', cancelledAt: '2030-01-01T10:01:00Z',
  });
});
afterEach(cleanup);

test('customer selects a chair slot and creates an approval request', async () => {
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('heading', { name: 'Koltuk seç.' });
  expect(await screen.findByTestId('shop-scene')).toBeTruthy();
  expect(screen.getByRole('button', { name: /Deniz Usta/ })).toBeTruthy();
  await screen.findByRole('button', { name: '12:00' });
  fireEvent.click(screen.getByRole('button', { name: '12:00' }));
  fireEvent.click(screen.getByRole('button', { name: 'Randevuyu onayla' }));

  await waitFor(() => expect(createBookingRequest).toHaveBeenCalledWith(expect.objectContaining({
    tenantSlug: 'moda', staffId: 'staff-1', serviceId: 'service-1', startsAt: availability.slots[0].startsAt,
  })));
  await screen.findByRole('heading', { name: 'İşletme onayı bekleniyor.' });
  expect(screen.getByText(/Kalan değerlendirme süresi/)).toBeTruthy();
});

test('trusted customer sees an explicit automatic confirmation result', async () => {
  vi.mocked(createBookingRequest).mockResolvedValueOnce({
    id: 'request-auto', tenantSlug: 'moda', resultType: 'APPOINTMENT', serviceId: 'service-1', staffId: 'staff-1',
    startsAt: availability.slots[0].startsAt, status: 'AUTO_CONFIRMED', trustState: 'TRUSTED',
    decision: 'AUTO_CONFIRM', holdExpiresAt: null, appointmentId: 'appointment-auto', canCancel: true,
    cancellableUntil: '2030-01-07T08:00:00Z', version: 1, appointmentVersion: 1, canReschedule: true, reschedulableUntil: '2030-01-07T08:00:00Z',
  });
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('button', { name: '12:00' });
  fireEvent.click(screen.getByRole('button', { name: '12:00' }));
  fireEvent.click(screen.getByRole('button', { name: 'Randevuyu onayla' }));
  expect(await screen.findByRole('heading', { name: 'Randevun kesinleşti.' })).toBeTruthy();
  expect(screen.getByText(/Doğrulanmış müşteri hesabın sayesinde/)).toBeTruthy();
});

test('customer can cancel from the booking result', async () => {
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('button', { name: '12:00' });
  fireEvent.click(screen.getByRole('button', { name: '12:00' }));
  fireEvent.click(screen.getByRole('button', { name: 'Randevuyu onayla' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Rezervasyonu iptal et' }));
  await waitFor(() => expect(cancelBookingRequest).toHaveBeenCalledWith(expect.objectContaining({ id: 'request-1', version: 1 })));
  await screen.findByRole('heading', { name: 'Rezervasyonun iptal edildi.' });
});

test('pending result refreshes automatically when the barber approves it', async () => {
  vi.mocked(getBookingRequest).mockResolvedValueOnce({
    id: 'request-1', tenantSlug: 'moda', resultType: 'APPOINTMENT', serviceId: 'service-1', staffId: 'staff-1',
    startsAt: availability.slots[0].startsAt, status: 'APPROVED', trustState: 'NEW',
    decision: 'REQUIRE_APPROVAL', holdExpiresAt: null, appointmentId: 'appointment-1',
    canCancel: true, cancellableUntil: '2030-01-07T08:00:00Z', version: 2, appointmentVersion: 2, canReschedule: true, reschedulableUntil: '2030-01-07T08:00:00Z',
  });
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('button', { name: '12:00' });
  fireEvent.click(screen.getByRole('button', { name: '12:00' }));
  fireEvent.click(screen.getByRole('button', { name: 'Randevuyu onayla' }));
  await screen.findByRole('heading', { name: 'Randevun kesinleşti.' });
  expect(getBookingRequest).toHaveBeenCalledWith('request-1');
});
