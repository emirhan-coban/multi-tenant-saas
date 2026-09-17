import { afterEach, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { AppRoutes } from '../src/router/AppRoutes';
import { CustomerAuthProvider } from '../src/store/customer-auth';
import { cancelBookingRequest, createVisitCode, getCustomerBookings, getCustomerNotifications, getRescheduleAvailability, rescheduleBooking } from '../src/api/customer-booking';
import { restoreCustomerSession } from '../src/api/customer-client';

vi.mock('../src/api/customer-booking', () => ({
  getCustomerBookings: vi.fn(), cancelBookingRequest: vi.fn(), createVisitCode: vi.fn(),
  getCustomerNotifications: vi.fn(), markCustomerNotificationRead: vi.fn(), downloadBookingCalendar: vi.fn(),
  getRescheduleAvailability: vi.fn(), rescheduleBooking: vi.fn(),
  getPublicShop: vi.fn(), getAvailability: vi.fn(), createBookingRequest: vi.fn(), getBookingRequest: vi.fn(),
}));
vi.mock('../src/api/customer-client', () => ({
  customerApi: { get: vi.fn(), post: vi.fn() }, authenticateCustomer: vi.fn(), resetCustomerSession: vi.fn(),
  restoreCustomerSession: vi.fn(), subscribeCustomerSession: () => () => {},
}));

const session = { accessToken: 'access', refreshToken: 'refresh', customer: {
  id: 'customer-1', email: 'ada@example.com', fullName: 'Ada Lovelace', phone: '+90 555 000 00 00',
} };
const booking = {
  id: 'request-1', tenantSlug: 'moda', resultType: 'APPOINTMENT' as const, tenantName: 'Moda Berber', timezone: 'Europe/Istanbul',
  serviceId: 'service-1', serviceName: 'Saç Kesimi', staffId: 'staff-1', staffName: 'Deniz Usta',
  startsAt: '2030-01-07T09:00:00Z', busyUntil: '2030-01-07T09:40:00Z', status: 'APPROVED' as const,
  appointmentStatus: 'CONFIRMED' as const, trustState: 'NEW' as const, decision: 'REQUIRE_APPROVAL' as const,
  holdExpiresAt: null, appointmentId: 'appointment-1', canCancel: true,
  cancellableUntil: '2030-01-07T08:00:00Z', canReschedule: true, reschedulableUntil: '2030-01-07T08:00:00Z',
  version: 2, appointmentVersion: 3, rescheduleRequestId: null, rescheduleStatus: null, proposedStartsAt: null, rejectionReason: null,
  createdAt: '2030-01-01T10:00:00Z', cancelledAt: null,
};

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
});
beforeEach(() => {
  vi.mocked(getCustomerNotifications).mockResolvedValue([]);
  vi.mocked(getRescheduleAvailability).mockResolvedValue({ timezone: 'Europe/Istanbul', date: '2030-01-08', slots: [{ startsAt: '2030-01-08T10:00:00Z', endsAt: '2030-01-08T10:30:00Z' }] });
  vi.mocked(rescheduleBooking).mockResolvedValue({ ...booking, rescheduleRequestId: 'move-1', rescheduleStatus: 'PENDING_APPROVAL', proposedStartsAt: '2030-01-08T10:00:00Z' });
  vi.mocked(restoreCustomerSession).mockResolvedValue(session);
  vi.mocked(getCustomerBookings).mockResolvedValue({ items: [booking], summary: {
    pending: 0, upcoming: 1, completed: 4, cancelled: 1, noShow: 1,
  } });
  vi.mocked(cancelBookingRequest).mockResolvedValue({ ...booking, status: 'CANCELLED', appointmentStatus: 'CANCELLED', canCancel: false, cancellableUntil: null, cancelledAt: '2030-01-02T10:00:00Z' });
  vi.mocked(createVisitCode).mockResolvedValue({ appointmentId: 'appointment-1', code: '482913', expiresAt: '2030-01-07T08:45:00Z' });
});

test('customer creates a short-lived visit code for a confirmed appointment', async () => {
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/customer/account']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('heading', { name: 'Merhaba, Ada Lovelace' });
  fireEvent.click(await screen.findByRole('button', { name: 'Ziyaret kodu' }));
  expect(await screen.findByLabelText('6 haneli ziyaret kodu')).toHaveProperty('textContent', '482913');
  expect(createVisitCode).toHaveBeenCalledWith('request-1');
});
afterEach(cleanup);

test('account shows summary and lets the customer cancel an upcoming appointment', async () => {
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/customer/account']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('heading', { name: 'Merhaba, Ada Lovelace' });
  expect(await screen.findByText('Saç Kesimi')).toBeTruthy();
  expect(screen.getByText('4')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'İptal et' }));
  await screen.findByRole('heading', { name: 'Rezervasyonu iptal et' });
  fireEvent.click(screen.getByRole('button', { name: 'Randevuyu iptal et' }));
  await waitFor(() => expect(cancelBookingRequest).toHaveBeenCalledWith(expect.objectContaining({ id: 'request-1', version: 2, appointmentVersion: 3 })));
  expect(getCustomerBookings).toHaveBeenCalledTimes(2);
});

test('history tab exposes completed cancelled and no-show totals', async () => {
  vi.mocked(getCustomerBookings).mockResolvedValue({ items: [{ ...booking, startsAt: '2020-01-07T09:00:00Z', appointmentStatus: 'COMPLETED', canCancel: false }], summary: {
    pending: 0, upcoming: 0, completed: 4, cancelled: 1, noShow: 1,
  } });
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/customer/account']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('heading', { name: 'Merhaba, Ada Lovelace' });
  fireEvent.click(screen.getByRole('tab', { name: 'Geçmiş' }));
  expect(await screen.findByText('Tamamlandı')).toBeTruthy();
  expect(screen.getByText('Gidilmeyen')).toBeTruthy();
});

test('reviewed customer sees that the old appointment survives a pending move', async () => {
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/customer/account']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('heading', { name: 'Merhaba, Ada Lovelace' });
  fireEvent.click(await screen.findByRole('button', { name: 'Taşı' }));
  fireEvent.change(screen.getByLabelText('Yeni tarih'), { target: { value: '2030-01-08' } });
  fireEvent.click(await screen.findByRole('button', { name: '13:00' }));
  expect(await screen.findByText(/Eski randevun onay gelene kadar korunuyor/)).toBeTruthy();
  expect(rescheduleBooking).toHaveBeenCalledWith(expect.objectContaining({ id: 'request-1', appointmentVersion: 3 }), '2030-01-08T10:00:00Z');
});
