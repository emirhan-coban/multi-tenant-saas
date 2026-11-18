import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { AppRoutes } from '../src/router/AppRoutes';
import { CustomerAuthProvider } from '../src/store/customer-auth';
import { cancelBookingRequest, createBookingRequest, getAvailability, getBookingRequest, getPublicScene, getPublicShop, getShopFeatures } from '../src/api/customer-booking';
import { restoreCustomerSession } from '../src/api/customer-client';

vi.mock('../src/components/BarberShopScene', () => ({
  BarberShopScene: () => <div data-testid="shop-scene">3D shop</div>,
}));

vi.mock('../src/api/customer-booking', () => ({
  getPublicShop: vi.fn(),
  getPublicScene: vi.fn(),
  getShopFeatures: vi.fn(),
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
  vi.mocked(getShopFeatures).mockResolvedValue({ threeD: true, pushNotifications: true, pwaInstall: true });
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
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function confirmThroughSteps() {
  fireEvent.click(screen.getByRole('button', { name: 'Devam et' }));
  fireEvent.click(screen.getByRole('button', { name: 'Devam et' }));
  fireEvent.click(screen.getByRole('button', { name: 'Randevuyu onayla' }));
}

test('customer selects a chair slot and creates an approval request', async () => {
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('heading', { name: 'Randevun' });
  expect(await screen.findByTestId('shop-scene')).toBeTruthy();
  expect(screen.getByText('Deniz · Koltuk 01')).toBeTruthy();
  await screen.findByRole('button', { name: '12:00' });
  fireEvent.click(screen.getByRole('button', { name: '12:00' }));
  confirmThroughSteps();

  await waitFor(() => expect(createBookingRequest).toHaveBeenCalledWith(expect.objectContaining({
    tenantSlug: 'moda', staffId: 'staff-1', serviceId: 'service-1', startsAt: availability.slots[0].startsAt,
  })));
  await screen.findByRole('heading', { name: 'Talebin gönderildi.' });
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
  confirmThroughSteps();
  // Faz 10: otomatik onaylanan müşteriye randevunun oluştuğu söylenir.
  expect(await screen.findByRole('heading', { name: 'Randevun oluşturuldu.' })).toBeTruthy();
  expect(screen.getByText(/Doğrulanmış müşteri hesabın sayesinde/)).toBeTruthy();
});

test('customer can cancel from the booking result', async () => {
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  await screen.findByRole('button', { name: '12:00' });
  fireEvent.click(screen.getByRole('button', { name: '12:00' }));
  confirmThroughSteps();
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
  confirmThroughSteps();
  await screen.findByRole('heading', { name: 'Randevun kesinleşti.' });
  expect(getBookingRequest).toHaveBeenCalledWith('request-1');
});

test('list view keeps the same selection and shows first available time per barber', async () => {
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda?view=list']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  const barber = await screen.findByRole('button', { name: /Deniz Usta/ });
  expect(barber.getAttribute('aria-pressed')).toBe('true');
  expect(await screen.findByText('İlk müsait 12:00')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Devam et' }).hasAttribute('disabled')).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: '12:00' }));
  fireEvent.click(screen.getByRole('button', { name: 'Devam et' }));
  expect(await screen.findByText('ada@example.com')).toBeTruthy();
  fireEvent.change(screen.getByPlaceholderText(/yanlar kısa/), { target: { value: 'Kısa olsun' } });
  fireEvent.click(screen.getByRole('button', { name: 'Devam et' }));
  fireEvent.click(screen.getByRole('button', { name: 'Randevuyu onayla' }));
  await waitFor(() => expect(createBookingRequest).toHaveBeenCalledWith(expect.objectContaining({ source: 'WEB_LIST', note: 'Kısa olsun' })));
});

test('3D kill switch açıkken sayfa liste görünümüyle açılır ve rezervasyon WEB_LIST olarak gider', async () => {
  vi.mocked(getShopFeatures).mockResolvedValue({ threeD: false, pushNotifications: true, pwaInstall: true });
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  expect(await screen.findByRole('button', { name: /Deniz Usta/ })).toBeTruthy();
  await waitFor(() => expect(screen.getByRole('button', { name: /3D görünüm/ }).hasAttribute('disabled')).toBe(true));
  expect(screen.queryByTestId('shop-scene')).toBeNull();
  fireEvent.click(await screen.findByRole('button', { name: '12:00' }));
  confirmThroughSteps();
  await waitFor(() => expect(createBookingRequest).toHaveBeenCalledWith(expect.objectContaining({ source: 'WEB_LIST' })));
  expect(vi.mocked(createBookingRequest).mock.calls[0][0]).not.toHaveProperty('chairKey');
});

test('gönderim sırasında 3D kapatılırsa seçimler korunur ve aynı talep listeden tekrar gönderilir', async () => {
  vi.mocked(createBookingRequest).mockRejectedValueOnce({ isAxiosError: true, response: { status: 409, data: { code: 'FEATURE_DISABLED', message: 'kapalı' } } });
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  expect(await screen.findByTestId('shop-scene')).toBeTruthy();
  fireEvent.click(await screen.findByRole('button', { name: '12:00' }));
  confirmThroughSteps();
  expect(await screen.findByText(/3D görünüm şu anda kapalı. Seçimlerin korundu/)).toBeTruthy();
  expect(vi.mocked(createBookingRequest).mock.calls[0][0]).toMatchObject({ source: 'WEB_3D', chairKey: 'chair-1' });
  expect(screen.queryByTestId('shop-scene')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Randevuyu onayla' }));
  await screen.findByRole('heading', { name: 'Talebin gönderildi.' });
  expect(vi.mocked(createBookingRequest).mock.calls[1][0]).toMatchObject({ source: 'WEB_LIST', staffId: 'staff-1', startsAt: availability.slots[0].startsAt });
});

test('bayrak uç noktası yoksa (eski sunucu) 3D akışı bozulmaz', async () => {
  vi.mocked(getShopFeatures).mockRejectedValue(new Error('404'));
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  expect(await screen.findByTestId('shop-scene')).toBeTruthy();
});

test('çevrimdışıyken onay düğmesi pasif ve durum duyurulur; adım değişince odak panele taşınır', async () => {
  let connected = false;
  Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => connected });
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);
  fireEvent.click(await screen.findByRole('button', { name: '12:00' }));
  expect(screen.getByText(/İnternet bağlantısı yok/)).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Devam et' }).hasAttribute('disabled')).toBe(true);
  connected = true;
  window.dispatchEvent(new Event('online'));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Devam et' }).hasAttribute('disabled')).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: 'Devam et' }));
  await waitFor(() => expect(document.activeElement?.id).toBe('booking-panel-title'));
  expect(screen.getByRole('link', { name: 'Randevu paneline geç' }).getAttribute('href')).toBe('#booking-panel');
  delete (navigator as unknown as Record<string, unknown>).onLine;
});

test('çalışan yokken müşteri açıklayıcı mesaj görür, 3D sahne boş koltuklarla açılır ve randevu alınamaz', async () => {
  vi.mocked(getPublicShop).mockResolvedValue({ ...shop, staff: [] });
  vi.mocked(getPublicScene).mockResolvedValue({ ...manifest, chairs: [] });
  render(<CustomerAuthProvider><MemoryRouter initialEntries={['/book/moda']}><AppRoutes /></MemoryRouter></CustomerAuthProvider>);

  expect((await screen.findAllByText(/randevu alınabilecek berber yok/)).length).toBeGreaterThan(0);
  expect(await screen.findByTestId('shop-scene')).toBeTruthy();
  expect(createBookingRequest).not.toHaveBeenCalled();
  const next = screen.queryByRole('button', { name: 'Devam et' });
  if (next) expect((next as HTMLButtonElement).disabled).toBe(true);
});
