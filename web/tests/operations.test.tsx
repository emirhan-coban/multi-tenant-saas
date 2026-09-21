import { cleanup, fireEvent, render, screen, waitFor, act } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { TenantProvider } from '../src/store/tenant';
import { UsageCard, PlanScreen, ReportsScreen, AuditScreen, NotificationsScreen, OperationalFeedback, reportRange } from '../src/screens/OperationsScreens';
import { api } from '../src/api/client';
import { errorMessage } from '../src/api/errors';
import type { ReactNode } from 'react';
vi.mock('../src/api/client', () => ({ api: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() } }));
const usage = { plan: 'FREE', staff: { used: 2, limit: 2 }, appointments: { used: 95, limit: 100 }, resetsAt: '2030-02-01T00:00:00Z', timezone: 'Europe/Istanbul', requestsPerMinute: 60 };
function mount(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><TenantProvider user={{ userId: 'user', tenantId: 'tenant', role: 'OWNER' }}><MemoryRouter>{node}</MemoryRouter></TenantProvider></QueryClientProvider>);
}
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);
test('usage renders real limits and upgrade link when full', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: usage }); mount(<UsageCard />);
  expect((await screen.findByRole('progressbar', { name: 'Aktif personel' })).getAttribute('value')).toBe('2');
  expect(screen.getByText('95 / 100')).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Plan seçeneklerini inceleyin' }).getAttribute('href')).toBe('/app/plan');
});
test('pro displays unlimited without an invalid progress bar', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: { ...usage, plan: 'PRO', staff: { used: 6, limit: null }, appointments: { used: 800, limit: null } } });
  mount(<UsageCard />); await screen.findByText('6 / Sınırsız'); expect(screen.queryByRole('progressbar')).toBeNull();
});
test('upgrade opens provider checkout form without treating the browser as payment proof', async () => {
  vi.mocked(api.get).mockImplementation(async path => ({ data:path==='/api/billing/subscription'
    ? {planCode:'PRO_MONTHLY',planName:'Profesyonel',amountMinor:49900,currency:'TRY',status:'FREE',paidFeaturesEnabled:false,trialEndsAt:null,periodEndsAt:null,graceEndsAt:null,cancelAtPeriodEnd:false}
    : usage }));
  mount(<PlanScreen />);
  fireEvent.click(await screen.findByRole('button', { name: '14 günlük denemeyi başlat' }));
  expect(screen.getByRole('heading',{name:'Profesyonel plana geç'})).toBeTruthy();
  expect(screen.getByText(/doğrudan sağlayıcıya iletilir/)).toBeTruthy();
  expect(api.post).not.toHaveBeenCalled();
});
test('reports show revenue, chart values and unknown historical prices', async () => {
  vi.mocked(api.get).mockImplementation(async path => ({ data: path.endsWith('summary')
    ? { appointmentCount: 8, completedCount: 3, revenue: 450, unknownPriceCount: 1, popularService: 'Kesim', services: [{ name: 'Kesim', count: 5 }] }
    : [{ id: 's', name: 'Ayşe', availableMinutes: 480, occupiedMinutes: 120, percent: 25 }] }));
  mount(<ReportsScreen />); await screen.findByText('8');
  expect(screen.getByText(/eski randevunun geçmiş fiyatı/)).toBeTruthy();
  expect(screen.getByRole('list', { name: 'Personel doluluk oranları' }).textContent).toContain('25%');
});
test('invalid report dates do not send a query', async () => {
  vi.mocked(api.get).mockResolvedValue({ data: [] }); mount(<AuditScreen />);
  await waitFor(() => expect(api.get).toHaveBeenCalled());
  vi.mocked(api.get).mockClear();
  fireEvent.change(screen.getByLabelText('Başlangıç tarihi'), { target: { value: '2040-01-01' } });
  await screen.findByRole('alert'); expect(api.get).not.toHaveBeenCalled();
});
test('range is inclusive through the final local day and bounded', () => {
  expect(reportRange('2030-01-01', '2030-01-01').valid).toBe(true);
  expect(reportRange('2030-01-03', '2030-01-01').valid).toBe(false);
  expect(reportRange('2030-01-01', '2030-06-01').valid).toBe(false);
  expect(reportRange('', '').valid).toBe(false);
});
test('audit supports actor filter and displays immutable history', async () => {
  vi.mocked(api.get).mockImplementation(async path => ({ data: path.endsWith('actors')
    ? [{ id: 'u1', name: 'Emre' }]
    : [{ id: '1', actorName: 'Emre', action: 'SERVICE_PRICE_CHANGED', entityType: 'service', entityId: 's1', createdAt: '2030-01-01T09:00:00Z', details: '{"before":100,"after":200}' }] }));
  mount(<AuditScreen />); await screen.findByText('Hizmet fiyatı değiştirildi');
  fireEvent.change(screen.getByLabelText('İşlemi yapan'), { target: { value: 'u1' } });
  await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/audit-log', expect.objectContaining({ params: expect.objectContaining({ actorId: 'u1' }) })));
});
test('notification preference saves and reloads persisted value', async () => {
  vi.mocked(api.get).mockImplementation(async path => ({ data: path === '/api/notifications/in-app' ? [] : path.endsWith('email-status') ? {enabled:false,configured:false,ready:false,mode:'test'} : { remindersEnabled: true } }));
  vi.mocked(api.patch).mockResolvedValue({ data: { remindersEnabled: false } });
  mount(<NotificationsScreen />);
  const checkbox = await screen.findByRole('checkbox');
  vi.mocked(api.get).mockImplementation(async path => ({ data: path === '/api/notifications/in-app' ? [] : path.endsWith('email-status') ? {enabled:false,configured:false,ready:false,mode:'test'} : { remindersEnabled: false } }));
  fireEvent.click(checkbox);
  await screen.findByText('Tercihiniz kaydedildi.');
  expect(api.patch).toHaveBeenCalledWith('/api/notification-preferences', { remindersEnabled: false });
  await waitFor(() => expect((checkbox as HTMLInputElement).checked).toBe(false));
});
test('quota and 429 are not presented as generic permission errors', () => {
  const error = { isAxiosError: true, response: { status: 403, data: { code: 'QUOTA_EXCEEDED', message: 'Kota doldu' } } };
  expect(errorMessage(error, 'Fallback')).toBe('Kota doldu');
  expect(errorMessage({ isAxiosError: true, response: { status: 429, headers: { 'retry-after': '17' } } }, 'Fallback')).toContain('17 saniye');
});
test('quota feedback has a dismissible upgrade action', async () => {
  mount(<OperationalFeedback />);
  act(() => window.dispatchEvent(new CustomEvent('saas:api-error', { detail: { message: 'Kota doldu', quota: true } })));
  expect(screen.getByRole('alert').textContent).toContain('Kota doldu');
  expect(screen.getByRole('link').getAttribute('href')).toBe('/app/plan');
  fireEvent.click(screen.getByRole('button', { name: 'Kapat' })); expect(screen.queryByRole('alert')).toBeNull();
});
test('failed notification save is visible and preserves last confirmed preference', async () => {
  vi.mocked(api.get).mockImplementation(async path => ({ data: path === '/api/notifications/in-app' ? [] : path.endsWith('email-status') ? {enabled:false,configured:false,ready:false,mode:'test'} : { remindersEnabled: true } }));
  vi.mocked(api.patch).mockRejectedValue(new Error('Offline'));
  mount(<NotificationsScreen />); const checkbox = await screen.findByRole('checkbox'); fireEvent.click(checkbox);
  await screen.findByRole('alert'); expect((checkbox as HTMLInputElement).checked).toBe(true);
});
test('email status explains safe test mode without exposing credentials', async () => {
  vi.mocked(api.get).mockImplementation(async path => ({data: path === '/api/notifications/in-app' ? [] : path.endsWith('email-status') ? {enabled:false,configured:false,ready:false,mode:'test'} : {remindersEnabled:true}}));
  mount(<NotificationsScreen />);
  await screen.findByText('Gmail gönderimi kapalı veya hesap bilgileri eksik.');
  expect(screen.getByText(/Test modu: yalnızca gönderici Gmail adresine/)).toBeTruthy();
  expect(screen.queryByLabelText(/şifre/i)).toBeNull();
});
