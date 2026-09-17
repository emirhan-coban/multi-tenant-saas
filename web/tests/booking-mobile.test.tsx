import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { AppRoutes } from '../src/router/AppRoutes';
import { CustomerAuthProvider } from '../src/store/customer-auth';
import { createBookingRequest, getAvailability, getBookingRequest, getPublicScene, getPublicShop, getShopFeatures } from '../src/api/customer-booking';
import { restoreCustomerSession } from '../src/api/customer-client';

/**
 * Faz 10 — telefon akışı. Masaüstü düzeni ayrı testlerde (booking-flow) korunuyor;
 * burada yalnızca dar ekranda devreye giren aşamalı akış sınanır.
 */

vi.mock('../src/components/BarberShopScene', () => ({
  BarberShopScene: ({ onReady }: { onReady?: () => void }) => {
    onReady?.();
    return <div data-testid="shop-scene">3D shop</div>;
  },
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
const staff = [
  { id: 'staff-1', displayName: 'Emre Kaya', chairKey: 'chair-1' },
  { id: 'staff-2', displayName: 'Ahmet Yılmaz Karadeniz', chairKey: 'chair-2' },
];
const shop = {
  id: 'tenant-1', slug: 'moda', name: 'Moda Berber', timezone: 'Europe/Istanbul',
  services: [
    { id: 'service-1', name: 'Saç kesimi', durationMin: 45, bufferMin: 0, price: 600 },
    { id: 'service-2', name: 'Sakal tıraşı', durationMin: 30, bufferMin: 0, price: 350 },
  ],
  staff,
};
const availability = {
  timezone: 'Europe/Istanbul', date: '2030-01-07',
  slots: [
    { startsAt: '2030-01-07T09:00:00Z', endsAt: '2030-01-07T09:45:00Z' },
    { startsAt: '2030-01-07T10:00:00Z', endsAt: '2030-01-07T10:45:00Z' },
  ],
};
const manifest = {
  version: 1, templateKey: 'CLASSIC_3_CHAIR' as const, accentColor: '#245e50',
  chairs: staff.map((person, index) => ({
    chairKey: person.chairKey, label: person.displayName, staffId: person.id, staffName: person.displayName,
    positionX: index * 3.25, positionZ: -1.25, rotationY: 0,
  })),
};

/** Dar ekran: yalnızca genişlik sorgusu eşleşir, hareket azaltma kapalıdır. */
function compactScreen() {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('max-width: 840px') || query.includes('pointer: coarse'),
    media: query, onchange: null,
    addEventListener: () => {}, removeEventListener: () => {},
    addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

beforeEach(() => {
  compactScreen();
  Element.prototype.scrollIntoView = vi.fn();
  vi.mocked(restoreCustomerSession).mockResolvedValue(session);
  vi.mocked(getPublicShop).mockResolvedValue(shop);
  vi.mocked(getPublicScene).mockResolvedValue(manifest);
  vi.mocked(getShopFeatures).mockResolvedValue({ threeD: true, pushNotifications: true, pwaInstall: true });
  vi.mocked(getAvailability).mockResolvedValue(availability);
  vi.mocked(getBookingRequest).mockResolvedValue(null as never);
  vi.mocked(createBookingRequest).mockResolvedValue({
    id: 'request-1', tenantSlug: 'moda', resultType: 'REQUEST', serviceId: 'service-1', staffId: 'staff-1',
    startsAt: availability.slots[0].startsAt, status: 'PENDING_APPROVAL', trustState: 'NEW',
    decision: 'REQUIRE_APPROVAL', holdExpiresAt: '2030-01-07T08:15:00Z', appointmentId: null,
    canCancel: true, cancellableUntil: '2030-01-07T08:15:00Z', version: 1, appointmentVersion: null,
    canReschedule: false, reschedulableUntil: null,
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function mount() {
  return render(<MemoryRouter initialEntries={['/book/moda']}><CustomerAuthProvider><AppRoutes /></CustomerAuthProvider></MemoryRouter>);
}

async function stage() {
  return (await screen.findByRole('main')).getAttribute('data-stage');
}

test('telefonda akış berber seçimiyle başlar; hizmet çipleri ve berber şeridi görünür', async () => {
  mount();
  await screen.findByRole('heading', { name: 'Berberini seç' });
  expect(await stage()).toBe('barber');

  // Hizmet sahnenin üstünde: koltuk etiketlerindeki ilk müsait saat baştan doğru olur.
  const services = screen.getByRole('radiogroup', { name: 'Hizmet' });
  expect(within(services).getAllByRole('radio')).toHaveLength(2);

  // İsimler sahnede değil, şeritte: uzun isimler koltukta çakışmaz.
  const strip = screen.getByRole('navigation', { name: 'Berber seçimi' });
  expect(within(strip).getAllByRole('button')).toHaveLength(2);
  expect(within(strip).getByText('Ahmet Yılmaz Karadeniz')).toBeTruthy();
});

test('koltuk seçilince saat aşamasına geçilir, sahne sökülür ve seçim görünür kalır', async () => {
  mount();
  await screen.findByRole('heading', { name: 'Berberini seç' });
  expect(screen.getByTestId('shop-scene')).toBeTruthy();

  fireEvent.click(screen.getByRole('button', { name: /Saat seç/ }));

  await screen.findByRole('heading', { name: 'Saatini seç' });
  expect(await stage()).toBe('time');
  // Sahne tamamen sökülür: WebGL bağlamı saat seçerken çalışmaz.
  expect(screen.queryByTestId('shop-scene')).toBeNull();
  // Seçilen berber donmuş şeritte görünmeye devam eder.
  expect(screen.getByRole('button', { name: 'Değiştir' })).toBeTruthy();
  await waitFor(() => expect(screen.getAllByRole('button', { name: /^\d{2}:\d{2}$/ }).length).toBeGreaterThan(0));
});

test('geri tuşu bir aşama geri alır, dükkândan çıkarmaz', async () => {
  mount();
  await screen.findByRole('heading', { name: 'Berberini seç' });
  fireEvent.click(screen.getByRole('button', { name: /Saat seç/ }));
  await screen.findByRole('heading', { name: 'Saatini seç' });

  fireEvent.popState(window, { state: { bkDepth: 0 } });

  await screen.findByRole('heading', { name: 'Berberini seç' });
  expect(await stage()).toBe('barber');
  expect(screen.getByTestId('shop-scene')).toBeTruthy();
});

test('“Değiştir” berber seçimine döner', async () => {
  mount();
  await screen.findByRole('heading', { name: 'Berberini seç' });
  fireEvent.click(screen.getByRole('button', { name: /Saat seç/ }));
  await screen.findByRole('heading', { name: 'Saatini seç' });

  fireEvent.click(screen.getByRole('button', { name: 'Değiştir' }));

  await screen.findByRole('heading', { name: 'Berberini seç' });
  expect(await stage()).toBe('barber');
});

test('alt panel düğmeyle küçültülüp genişletilebilir', async () => {
  mount();
  await screen.findByRole('heading', { name: 'Berberini seç' });
  const toggle = screen.getByRole('button', { name: 'Paneli genişlet' });
  fireEvent.click(toggle);
  expect(screen.getByRole('button', { name: 'Paneli küçült' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Paneli küçült' }));
  expect(screen.getByRole('button', { name: 'Paneli genişlet' })).toBeTruthy();
});

test('şeritten berber seçmek sahneye ve özete yansır', async () => {
  mount();
  await screen.findByRole('heading', { name: 'Berberini seç' });
  const strip = screen.getByRole('navigation', { name: 'Berber seçimi' });

  fireEvent.click(within(strip).getAllByRole('button')[1]);

  await waitFor(() => expect(within(strip).getAllByRole('button')[1].getAttribute('aria-pressed')).toBe('true'));
  expect(screen.getByText('Ahmet')).toBeTruthy();
});

test('tek hizmetli dükkânda çip şeridi hiç çizilmez', async () => {
  vi.mocked(getPublicShop).mockResolvedValue({ ...shop, services: [shop.services[0]] });
  mount();
  await screen.findByRole('heading', { name: 'Berberini seç' });
  expect(screen.queryByRole('radiogroup', { name: 'Hizmet' })).toBeNull();
});

test('yeni müşteriye talebin gönderildiği, otomatik onayda randevunun oluştuğu söylenir', async () => {
  mount();
  await screen.findByRole('heading', { name: 'Berberini seç' });
  fireEvent.click(screen.getByRole('button', { name: /Saat seç/ }));
  await screen.findByRole('heading', { name: 'Saatini seç' });
  const slots = await screen.findAllByRole('button', { name: /^\d{2}:\d{2}$/ });
  fireEvent.click(slots[0]);
  fireEvent.click(screen.getByRole('button', { name: /Devam et/ }));
  await screen.findByRole('heading', { name: 'Bilgilerin' });
  fireEvent.click(screen.getByRole('button', { name: /Devam et/ }));
  await screen.findByRole('heading', { name: 'Son kontrol' });
  fireEvent.click(screen.getByRole('button', { name: /Randevuyu onayla/ }));

  await screen.findByRole('heading', { name: 'Talebin gönderildi.' });
});
