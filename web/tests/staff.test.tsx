import type { ReactNode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import { createQueryClient } from '../src/query/client';
import { TenantProvider } from '../src/store/tenant';
import { StaffScreen } from '../src/screens/StaffScreen';
import * as api from '../src/api/management';
import type { InviteResult, Staff } from '../src/api/types';
vi.mock('../src/api/management', () => ({ getStaff: vi.fn(), inviteStaff: vi.fn(), addSelfAsStaff: vi.fn(), getPendingInvites: vi.fn() }));
const person = { id: 'one', displayName: 'Deniz Yılmaz', active: true };
const result: InviteResult = { personelId: 'one', email: 'deniz@example.com', invitationSent: true };
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
});
beforeEach(() => { vi.resetAllMocks(); localStorage.clear(); sessionStorage.clear(); vi.mocked(api.getStaff).mockResolvedValue([]); vi.mocked(api.getPendingInvites).mockResolvedValue([]); });
afterEach(cleanup);

test('new staff refreshes list and exposes no credential in cache or storage', async () => {
  vi.mocked(api.inviteStaff).mockResolvedValue(result);
  const { client } = setup(); await screen.findByText('Henüz personel eklemediniz');
  fill(); vi.mocked(api.getStaff).mockResolvedValue([person]);
  fireEvent.click(screen.getByRole('button', { name: 'Personel oluştur' }));
  await screen.findByRole('heading', { name: 'Davet gönderildi' });
  await screen.findByText('Deniz Yılmaz');
  expect(screen.getByText('Tek kullanımlık davet bağlantısı personelin e-posta adresine gönderildi.')).toBeTruthy();
  expect(api.inviteStaff).toHaveBeenCalledWith({ displayName: 'Deniz Yılmaz', email: 'deniz@example.com' });
  expect(JSON.stringify(client.getQueryCache().getAll().map(query => query.state))).not.toContain('password');
  expect(JSON.stringify(client.getMutationCache().getAll().map(mutation => mutation.state))).not.toContain('password');
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

test('existing account uses the same email invitation flow', async () => {
  vi.mocked(api.inviteStaff).mockResolvedValue(result);
  setup(); await screen.findByText('Henüz personel eklemediniz'); fill();
  fireEvent.click(screen.getByRole('button', { name: 'Personel oluştur' }));
  await screen.findByRole('heading', { name: 'Davet gönderildi' });
  expect(screen.getByText('Bağlantı 48 saat geçerlidir ve yalnızca bir kez kullanılabilir. Personel kabul edene kadar erişim açılmaz.')).toBeTruthy();
  expect(screen.queryByLabelText('Geçici şifre')).toBeNull();
});

test('invitation result displays recipient but never a temporary password', async () => {
  vi.mocked(api.inviteStaff).mockResolvedValue(result);
  setup(); await screen.findByText('Henüz personel eklemediniz'); fill();
  fireEvent.click(screen.getByRole('button', { name: 'Personel oluştur' }));
  await screen.findByText('deniz@example.com');
  expect(screen.queryByLabelText('Geçici şifre')).toBeNull();
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
  await screen.findByRole('heading', { name: 'Davet gönderildi' });
  await screen.findByRole('alert');
  expect(screen.getByText('deniz@example.com')).toBeTruthy();
  expect(api.inviteStaff).toHaveBeenCalledOnce();
});

test('owner can add self as a barber and the button disappears', async () => {
  const self: Staff = { id: 'me', displayName: 'Ada Usta', active: true, self: true };
  vi.mocked(api.addSelfAsStaff).mockResolvedValue(self);
  setup(); await screen.findByText('Henüz personel eklemediniz');
  vi.mocked(api.getStaff).mockResolvedValue([self]);
  fireEvent.click(screen.getAllByRole('button', { name: 'Ben de berberim' })[0]);
  await screen.findByText('Ada Usta');
  expect(api.addSelfAsStaff).toHaveBeenCalledTimes(1);
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Ben de berberim' })).toBeNull());
});

test('when e-mail delivery is off the owner gets a copyable invitation link', async () => {
  vi.mocked(api.inviteStaff).mockResolvedValue({ ...result, inviteLink: 'https://app.example/personel-daveti?token=abc' });
  setup(); await screen.findByText('Henüz personel eklemediniz');
  fill(); fireEvent.click(screen.getByRole('button', { name: 'Personel oluştur' }));
  await screen.findByRole('heading', { name: 'Davet bağlantısı hazır' });
  expect((screen.getByLabelText('Davet bağlantısı') as HTMLInputElement).value).toBe('https://app.example/personel-daveti?token=abc');
  expect(screen.getByRole('button', { name: 'Bağlantıyı kopyala' })).toBeTruthy();
});

test('pending invitations are listed until accepted', async () => {
  vi.mocked(api.getPendingInvites).mockResolvedValue([
    { id: 'i1', email: 'yeni@example.com', displayName: 'Yeni Berber', createdAt: '2030-01-01T10:00:00Z', expiresAt: '2030-01-03T10:00:00Z', expired: false },
    { id: 'i2', email: 'eski@example.com', displayName: 'Eski Davet', createdAt: '2020-01-01T10:00:00Z', expiresAt: '2020-01-03T10:00:00Z', expired: true },
  ]);
  setup(); await screen.findByRole('region', { name: 'Bekleyen davetler' });
  expect(screen.getByText('Kabul bekleniyor')).toBeTruthy();
  expect(screen.getByText('Süresi doldu')).toBeTruthy();
});
