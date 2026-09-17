import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { AppRoutes } from '../src/router/AppRoutes';
import { AuthProvider } from '../src/store/auth';
import { createQueryClient } from '../src/query/client';
import { api, restoreSession } from '../src/api/client';
import QRCode from 'qrcode';

vi.mock('qrcode', () => ({ default: { toDataURL: vi.fn() } }));
vi.mock('../src/api/client', () => ({
  api: { get: vi.fn() }, restoreSession: vi.fn(), subscribeSession: () => () => {},
  resetSessionTransport: vi.fn(), authenticate: vi.fn(),
}));

const bookingLink = { tenantId: 'tenant-1', tenantName: 'Moda Berber', tenantSlug: 'moda-berber', timezone: 'Europe/Istanbul' };
const clipboard = { writeText: vi.fn() };

beforeEach(() => {
  vi.mocked(restoreSession).mockResolvedValue({ accessToken: 'token', refreshToken: 'refresh', memberships: [{ tenantId: 'tenant-1', tenantName: 'Moda Berber', role: 'STAFF' }] });
  vi.mocked(api.get).mockImplementation(async url => ({ data: url === '/api/me' ? { userId: 'user-1', tenantId: 'tenant-1', role: 'STAFF' } : bookingLink }));
  vi.mocked(QRCode.toDataURL).mockResolvedValue('data:image/png;base64,qr');
  clipboard.writeText.mockReset().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: clipboard });
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) { this.open = true; });
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) { this.open = false; });
});
afterEach(cleanup);

function mount() {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(<QueryClientProvider client={client}><AuthProvider><MemoryRouter initialEntries={['/app/booking-link']}><AppRoutes /></MemoryRouter></AuthProvider></QueryClientProvider>);
}

test('staff sees the verified shop URL and can copy or share it', async () => {
  mount();
  await screen.findByRole('heading', { name: 'Müşterilerin buradan randevu alsın.' });
  const expected = `${window.location.origin}/book/moda-berber`;
  expect((screen.getByLabelText('Halka açık adres') as HTMLInputElement).value).toBe(expected);
  await waitFor(() => expect(QRCode.toDataURL).toHaveBeenCalledWith(expected, expect.objectContaining({ errorCorrectionLevel: 'H' })));
  expect(await screen.findByAltText('Moda Berber rezervasyon QR kodu')).toHaveProperty('src', 'data:image/png;base64,qr');

  let downloaded: { href: string; filename: string } | null = null;
  const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    downloaded = { href: this.href, filename: this.download };
  });
  fireEvent.click(screen.getByRole('button', { name: 'QR indir' }));
  expect(downloaded).toEqual({ href: 'data:image/png;base64,qr', filename: 'moda-berber-rezervasyon-qr.png' });
  anchorClick.mockRestore();

  fireEvent.click(screen.getByRole('button', { name: 'Kopyala' }));
  await waitFor(() => expect(clipboard.writeText).toHaveBeenCalledWith(expected));
  expect((await screen.findByRole('status')).textContent).toContain('Bağlantı kopyalandı.');
  expect(screen.getByRole('link', { name: "WhatsApp'ta paylaş" }).getAttribute('href')).toContain('https://wa.me/?text=');
});

test('prepares a printable poster with the same QR code', async () => {
  const print = vi.spyOn(window, 'print').mockImplementation(() => undefined);
  mount();
  await screen.findByRole('button', { name: 'Afişi hazırla' });
  await waitFor(() => expect((screen.getByRole('button', { name: 'Afişi hazırla' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: 'Afişi hazırla' }));
  expect(screen.getByRole('heading', { name: 'Rezervasyon afişi' })).toBeTruthy();
  expect(screen.getByAltText('Yazdırılacak rezervasyon QR kodu')).toHaveProperty('src', 'data:image/png;base64,qr');
  fireEvent.click(screen.getByRole('button', { name: 'Yazdır' }));
  expect(print).toHaveBeenCalledOnce();
  print.mockRestore();
});
