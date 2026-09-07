import type { ReactNode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import { createQueryClient } from '../src/query/client';
import { TenantProvider } from '../src/store/tenant';
import { StaffScreen } from '../src/screens/StaffScreen';
import * as api from '../src/api/management';
import type { InviteResult, Staff } from '../src/api/types';
vi.mock('../src/api/management', () => ({ getStaff: vi.fn(), inviteStaff: vi.fn() }));
const person = { id: 'one', displayName: 'Deniz Yılmaz', active: true };
const result: InviteResult = { personelId: 'one', email: 'deniz@example.com', geciciSifre: 'temporary-secret' };
const copy = vi.fn();
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (error: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function setup() {
  const client = createQueryClient(); client.setDefaultOptions({ queries: { retry: false } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}><TenantProvider user={{ userId: 'user', tenantId: 'tenant', role: 'OWNER' }}>{children}</TenantProvider></QueryClientProvider>;
  const view = render(<StaffScreen />, { wrapper });
  return { client, ...view };
}
function fill() {
  fireEvent.click(screen.getByRole('button', { name: '+ Personel davet et' }));
  fireEvent.change(screen.getByLabelText('Ad soyad'), { target: { value: ' Deniz Yılmaz ' } });
  fireEvent.change(screen.getByLabelText('E-posta'), { target: { value: 'deniz@example.com' } });
}
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: copy } });
});
beforeEach(() => { vi.resetAllMocks(); localStorage.clear(); sessionStorage.clear(); vi.mocked(api.getStaff).mockResolvedValue([]); });
afterEach(cleanup);

test('new staff refreshes list; password copies and is never retained in query/mutation cache or storage', async () => {
  vi.mocked(api.inviteStaff).mockResolvedValue(result); copy.mockResolvedValue(undefined);
  const { client } = setup(); await screen.findByText('Henüz personel eklemediniz');
  fill(); vi.mocked(api.getStaff).mockResolvedValue([person]);
  fireEvent.click(screen.getByRole('button', { name: 'Personel oluştur' }));
  await screen.findByRole('heading', { name: 'Personel oluşturuldu' });
  await screen.findByText('Deniz Yılmaz');
  expect((screen.getByLabelText('Geçici şifre') as HTMLInputElement).value).toBe(result.geciciSifre);
  expect(api.inviteStaff).toHaveBeenCalledWith({ displayName: 'Deniz Yılmaz', email: 'deniz@example.com' });
  fireEvent.click(screen.getByRole('button', { name: 'Şifreyi kopyala' }));
  await screen.findByText('Şifre kopyalandı.');
  expect(copy).toHaveBeenCalledWith('temporary-secret');
  expect(JSON.stringify(client.getQueryCache().getAll().map(query => query.state))).not.toContain('temporary-secret');
  expect(JSON.stringify(client.getMutationCache().getAll().map(mutation => mutation.state))).not.toContain('temporary-secret');
  expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0);
  fireEvent.click(screen.getByRole('button', { name: 'Tamam' }));
  expect(screen.queryByLabelText('Geçici şifre')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '+ Personel davet et' }));
  expect((screen.getByLabelText('E-posta') as HTMLInputElement).value).toBe('');
  expect(screen.queryByLabelText('Geçici şifre')).toBeNull();
});

test('validation blocks invalid input; pending submission cannot be sent twice and error keeps input', async () => {
  const pending = deferred<InviteResult>(); vi.mocked(api.inviteStaff).mockReturnValue(pending.promise);
  setup(); await screen.findByText('Henüz personel eklemediniz');
  fill(); fireEvent.change(screen.getByLabelText('E-posta'), { target: { value: 'invalid' } });
  fireEvent.click(screen.getByRole('button', { name: 'Personel oluştur' }));
  expect(api.inviteStaff).not.toHaveBeenCalled();
  expect(screen.getByText('Geçerli bir e-posta adresi girin.')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('E-posta'), { target: { value: 'deniz@example.com' } });
  const form = screen.getByRole('button', { name: 'Personel oluştur' }).closest('form')!;
  fireEvent.submit(form); fireEvent.submit(form);
  await waitFor(() => expect(api.inviteStaff).toHaveBeenCalledOnce());
  expect(screen.getByRole('button', { name: 'Pencereyi kapat' }).hasAttribute('disabled')).toBe(true);
  await act(async () => { pending.reject(new Error('Failed')); });
  await screen.findByRole('alert');
  expect((screen.getByLabelText('E-posta') as HTMLInputElement).value).toBe('deniz@example.com');
  expect(screen.getByRole('button', { name: 'Personel oluştur' }).hasAttribute('disabled')).toBe(false);
});

test('existing account result has no password and does not claim an email was sent', async () => {
  vi.mocked(api.inviteStaff).mockResolvedValue({ ...result, geciciSifre: null });
  setup(); await screen.findByText('Henüz personel eklemediniz'); fill();
  fireEvent.click(screen.getByRole('button', { name: 'Personel oluştur' }));
  await screen.findByText('Bu e-posta mevcut bir hesaba ait. Personel, mevcut şifresiyle giriş yapabilir.');
  expect(screen.queryByLabelText('Geçici şifre')).toBeNull();
  expect(screen.getByText('E-posta gönderilmedi. Giriş bilgilerini personelle sizin paylaşmanız gerekiyor.')).toBeTruthy();
});

test('clipboard failure offers manual copying', async () => {
  vi.mocked(api.inviteStaff).mockResolvedValue(result); copy.mockRejectedValue(new Error('Denied'));
  setup(); await screen.findByText('Henüz personel eklemediniz'); fill();
  fireEvent.click(screen.getByRole('button', { name: 'Personel oluştur' }));
  await screen.findByLabelText('Geçici şifre');
  fireEvent.click(screen.getByRole('button', { name: 'Şifreyi kopyala' }));
  await screen.findByRole('alert');
  expect((screen.getByLabelText('Geçici şifre') as HTMLInputElement).value).toBe('temporary-secret');
});

test('loading, error, retry and empty state are distinct', async () => {
  const pending = deferred<Staff[]>(); vi.mocked(api.getStaff).mockReturnValue(pending.promise);
  setup(); expect(screen.getByLabelText('Personel yükleniyor')).toBeTruthy();
  await act(async () => { pending.reject(new Error('Offline')); });
  await screen.findByRole('alert'); expect(screen.queryByText('Henüz personel eklemediniz')).toBeNull();
  vi.mocked(api.getStaff).mockResolvedValue([]);
  fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }));
  await screen.findByText('Henüz personel eklemediniz');
});

test('successful invite result remains available if list refresh fails', async () => {
  vi.mocked(api.inviteStaff).mockResolvedValue(result);
  setup(); await screen.findByText('Henüz personel eklemediniz'); fill();
  vi.mocked(api.getStaff).mockRejectedValue(new Error('Offline'));
  fireEvent.click(screen.getByRole('button', { name: 'Personel oluştur' }));
  await screen.findByRole('heading', { name: 'Personel oluşturuldu' });
  await screen.findByRole('alert');
  expect((screen.getByLabelText('Geçici şifre') as HTMLInputElement).value).toBe('temporary-secret');
  expect(api.inviteStaff).toHaveBeenCalledOnce();
});
