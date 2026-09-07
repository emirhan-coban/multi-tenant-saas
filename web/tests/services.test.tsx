import { type ReactNode } from 'react';
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import { createQueryClient, clearSessionQueries } from '../src/query/client';
import { queryKeys } from '../src/query/keys';
import { TenantProvider } from '../src/store/tenant';
import { ServicesScreen } from '../src/screens/ServicesScreen';
import { useCreateService, type ServiceRow } from '../src/query/management';
import * as api from '../src/api/management';
vi.mock('../src/api/management', () => ({ getServices: vi.fn(), createService: vi.fn(), updateService: vi.fn(), deleteService: vi.fn() }));
const original = { id: 'one', name: 'Saç kesimi', durationMin: 30, price: 500, bufferMin: 5 };
const key = queryKeys.services('tenant');
function deferred<T>() { let resolve!: (v: T) => void; let reject!: (e: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function setup() {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}><TenantProvider user={{ userId: 'user', tenantId: 'tenant', role: 'OWNER' }}>{children}</TenantProvider></QueryClientProvider>;
  return { client, wrapper };
}
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
});
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.getServices).mockResolvedValue([original]); });
afterEach(cleanup);

test('optimistic failure removes the row and preserves draft for retry', async () => {
  const { wrapper } = setup();
  const pending = deferred<typeof original>();
  vi.mocked(api.createService).mockReturnValue(pending.promise);
  render(<ServicesScreen />, { wrapper });
  await screen.findByText('Saç kesimi');
  fireEvent.click(screen.getByRole('button', { name: '+ Hizmet ekle' }));
  fireEvent.change(screen.getByLabelText('Hizmet adı'), { target: { value: 'Bakım' } });
  fireEvent.change(screen.getByLabelText('Fiyat (₺)'), { target: { value: '150,50' } });
  fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }));
  await screen.findByText('Bakım');
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.getByRole('button', { name: 'Bakım hizmetini sil' }).hasAttribute('disabled')).toBe(true);
  await act(async () => { pending.reject(new Error('Server failed')); });
  await screen.findByRole('alert');
  await waitFor(() => expect(screen.queryByText('Bakım')).toBeNull());
  expect(screen.getByText('Saç kesimi')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Bilgileri aç ve tekrar dene' }));
  expect((screen.getByLabelText('Hizmet adı') as HTMLInputElement).value).toBe('Bakım');
  expect((screen.getByLabelText('Fiyat (₺)') as HTMLInputElement).value).toBe('150,50');
});

test('concurrent success survives another addition rollback', async () => {
  const { client, wrapper } = setup();
  client.setQueryData(key, [original]);
  const first = deferred<typeof original>(); const second = deferred<typeof original>();
  vi.mocked(api.createService).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
  const hook = renderHook(() => useCreateService(), { wrapper });
  let a!: Promise<unknown>; let b!: Promise<unknown>;
  act(() => { a = hook.result.current.mutateAsync({ ...original, name: 'A' }).catch(() => undefined); b = hook.result.current.mutateAsync({ ...original, name: 'B' }); });
  await waitFor(() => expect(client.getQueryData<ServiceRow[]>(key)?.length).toBe(3));
  await act(async () => { second.resolve({ ...original, id: 'two', name: 'B' }); await b; });
  await act(async () => { first.reject(new Error('Failed')); await a; });
  expect(client.getQueryData<ServiceRow[]>(key)?.map(row => row.name)).toEqual(['B', 'Saç kesimi']);
  expect(client.getQueryData<ServiceRow[]>(key)?.some(row => row.optimistic)).toBe(false);
});

test('refetch preserves pending rows; success replaces temporary ID without duplicates', async () => {
  const { client, wrapper } = setup();
  const pending = deferred<typeof original>();
  vi.mocked(api.createService).mockReturnValue(pending.promise);
  render(<ServicesScreen />, { wrapper });
  await screen.findByText('Saç kesimi');
  const hook = renderHook(() => useCreateService(), { wrapper });
  let mutation!: Promise<unknown>;
  act(() => { mutation = hook.result.current.mutateAsync({ ...original, name: 'Bakım' }); });
  await screen.findByText('Bakım');
  await act(async () => { await client.refetchQueries({ queryKey: key }); });
  expect(screen.getByText('Bakım')).toBeTruthy();
  vi.mocked(api.getServices).mockResolvedValue([original, { ...original, id: 'two', name: 'Bakım' }]);
  await act(async () => { pending.resolve({ ...original, id: 'two', name: 'Bakım' }); await mutation; });
  await waitFor(() => expect(client.getQueryData<ServiceRow[]>(key)?.every(row => !row.optimistic)).toBe(true));
  expect(screen.getAllByText('Bakım')).toHaveLength(1);
});

test('edit validates inputs and keeps values after server error; delete needs confirmation', async () => {
  const { wrapper } = setup();
  vi.mocked(api.updateService).mockRejectedValue(new Error('Failed'));
  render(<ServicesScreen />, { wrapper });
  await screen.findByText('Saç kesimi');
  fireEvent.click(screen.getByRole('button', { name: 'Saç kesimi hizmetini düzenle' }));
  fireEvent.change(screen.getByLabelText('Süre (dakika)'), { target: { value: '0' } });
  fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }));
  expect(api.updateService).not.toHaveBeenCalled();
  expect(screen.getByText('Süreyi pozitif bir tam sayı olarak girin.')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Süre (dakika)'), { target: { value: '45' } });
  fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }));
  await screen.findByRole('alert');
  expect((screen.getByLabelText('Süre (dakika)') as HTMLInputElement).value).toBe('45');
  fireEvent.click(screen.getByRole('button', { name: 'Vazgeç' }));
  fireEvent.click(screen.getByRole('button', { name: 'Saç kesimi hizmetini sil' }));
  expect(api.deleteService).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Vazgeç' }));
  expect(api.deleteService).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Saç kesimi hizmetini sil' }));
  vi.mocked(api.deleteService).mockResolvedValue(undefined);
  vi.mocked(api.getServices).mockResolvedValue([]);
  fireEvent.click(screen.getByRole('button', { name: 'Evet, hizmeti sil' }));
  await screen.findByText('Henüz hizmet eklemediniz');
  expect(api.deleteService).toHaveBeenCalledWith('one', expect.anything());
});

test('loading and error are distinct from empty state, retry recovers', async () => {
  const { wrapper } = setup(); const pending = deferred<typeof original[]>();
  vi.mocked(api.getServices).mockReturnValue(pending.promise);
  render(<ServicesScreen />, { wrapper });
  expect(screen.getByLabelText('Hizmetler yükleniyor')).toBeTruthy();
  await act(async () => { pending.reject(new Error('Offline')); });
  await screen.findByRole('alert');
  expect(screen.queryByText('Henüz hizmet eklemediniz')).toBeNull();
  vi.mocked(api.getServices).mockResolvedValue([]);
  fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }));
  await screen.findByText('Henüz hizmet eklemediniz');
});

test('logout during optimistic preparation prevents sending the create request', async () => {
  const { client, wrapper } = setup(); client.setQueryData(key, [original]);
  const cancellation = deferred<void>();
  vi.spyOn(client, 'cancelQueries').mockReturnValue(cancellation.promise);
  const hook = renderHook(() => useCreateService(), { wrapper });
  let mutation!: Promise<unknown>;
  act(() => { mutation = hook.result.current.mutateAsync(original).catch(error => error); });
  await waitFor(() => expect(client.cancelQueries).toHaveBeenCalled());
  clearSessionQueries(client);
  await act(async () => { cancellation.resolve(); await mutation; });
  expect(api.createService).not.toHaveBeenCalled();
  expect(client.getQueryCache().getAll()).toHaveLength(0);
});

test('delete failure keeps the service and confirmation open for retry', async () => {
  const { wrapper } = setup();
  vi.mocked(api.deleteService).mockRejectedValue(new Error('Failed'));
  render(<ServicesScreen />, { wrapper });
  await screen.findByText('Saç kesimi');
  fireEvent.click(screen.getByRole('button', { name: 'Saç kesimi hizmetini sil' }));
  fireEvent.click(screen.getByRole('button', { name: 'Evet, hizmeti sil' }));
  await screen.findByRole('alert');
  expect(screen.getByRole('dialog')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Saç kesimi hizmetini düzenle' })).toBeTruthy();
});
