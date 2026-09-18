import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { TenantProvider } from '../src/store/tenant';
import { BookingSettingsScreen } from '../src/screens/BookingSettingsScreen';
import * as management from '../src/api/management';

vi.mock('../src/api/management', () => ({ getBookingSettings: vi.fn(), updateBookingSettings: vi.fn() }));
const settings = { trustedAutoConfirmEnabled: true, maxFutureAppointments: 3, maxDailyBookings: 2,
  bookingHorizonDays: 30, minimumLeadMinutes: 60, requestExpiryMinutes: 15,
  cancellationCutoffMinutes: 120, version: 4, updatedAt: '2030-01-01T00:00:00Z' };

beforeEach(() => {
  vi.mocked(management.getBookingSettings).mockResolvedValue(settings);
  vi.mocked(management.updateBookingSettings).mockImplementation(async input => ({ ...input, version: input.version + 1, updatedAt: '2030-01-02T00:00:00Z' }));
});
afterEach(cleanup);

test('booking policy is saved with the server version', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><TenantProvider user={{ userId: 'owner', tenantId: 'tenant', role: 'OWNER' }}><BookingSettingsScreen /></TenantProvider></QueryClientProvider>);
  fireEvent.change(await screen.findByLabelText('İptal ve taşıma sınırı, dakika'), { target: { value: '180' } });
  fireEvent.click(screen.getByRole('button', { name: 'Ayarları kaydet' }));
  await waitFor(() => expect(vi.mocked(management.updateBookingSettings).mock.calls[0]?.[0]).toEqual(expect.objectContaining({ cancellationCutoffMinutes: 180, version: 4 })));
  expect(await screen.findByText('Rezervasyon ayarları kaydedildi.')).toBeTruthy();
});
