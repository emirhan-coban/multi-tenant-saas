import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, Link, RouterProvider } from 'react-router';
import { afterEach, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import { createQueryClient } from '../src/query/client';
import { queryKeys } from '../src/query/keys';
import { TenantProvider } from '../src/store/tenant';
import { WorkingHoursScreen } from '../src/screens/WorkingHoursScreen';
import { fromHours, weekPayload, weekErrors } from '../src/features/workingHours';
import * as api from '../src/api/management';
import type { WorkingHours } from '../src/api/types';
vi.mock('../src/api/management', () => ({ getStaff: vi.fn(), getWorkingHours: vi.fn(), saveWorkingHours: vi.fn() }));
const monday: WorkingHours = { id: 'hours', personelId: 'one', dayOfWeek: 0, startTime: '09:00:00', endTime: '18:00:00' };
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (e: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function mount() {
  const client = createQueryClient(); client.setDefaultOptions({ queries: { retry: false } });
  const router = createMemoryRouter([{ path: '/hours', element: <><Link to="/other">Başka sayfa</Link><WorkingHoursScreen /></> }, { path: '/other', element: <h1>Diğer sayfa</h1> }], { initialEntries: ['/hours'] });
  render(<QueryClientProvider client={client}><TenantProvider user={{ userId: 'user', tenantId: 'tenant', role: 'OWNER' }}><RouterProvider router={router} /></TenantProvider></QueryClientProvider>);
  return { client, router };
}
async function choose() { await screen.findByLabelText('Personel'); fireEvent.change(screen.getByLabelText('Personel'), { target: { value: 'one' } }); await screen.findByLabelText('Pazartesi açık'); }
beforeAll(() => { HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); }; HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); }; });
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.getStaff).mockResolvedValue([{ id: 'one', displayName: 'Deniz', active: true }, { id: 'two', displayName: 'Ece', active: true }]); vi.mocked(api.getWorkingHours).mockResolvedValue([monday]); });
afterEach(cleanup);

test('weekday shortcut changes only Monday-Friday and sends one complete week', async () => {
  mount(); await choose();
  expect(screen.getByRole('button', { name: 'Değişiklikleri kaydet' }).hasAttribute('disabled')).toBe(true);
  fireEvent.change(screen.getByLabelText('Pazartesi başlangıç'), { target: { value: '10:00' } });
  fireEvent.click(screen.getByRole('button', { name: 'Hafta içi hepsine uygula' }));
  expect((screen.getByLabelText('Cuma başlangıç') as HTMLInputElement).value).toBe('10:00');
  expect((screen.getByLabelText('Cumartesi açık') as HTMLInputElement).checked).toBe(false);
  expect(api.saveWorkingHours).not.toHaveBeenCalled();
  vi.mocked(api.saveWorkingHours).mockImplementation(async input => input.days.filter(day => !day.closed).map(day => ({ id: String(day.dayOfWeek), personelId: input.personelId, dayOfWeek: day.dayOfWeek, startTime: day.startTime!, endTime: day.endTime! })));
  fireEvent.click(screen.getByRole('button', { name: 'Değişiklikleri kaydet' }));
  await screen.findByText('Çalışma saatleri kaydedildi.');
  const input = vi.mocked(api.saveWorkingHours).mock.calls[0][0];
  expect(input.personelId).toBe('one'); expect(input.days).toHaveLength(7);
  expect(input.days[6]).toEqual({ dayOfWeek: 6, closed: true, startTime: null, endTime: null });
  expect(screen.getByRole('button', { name: 'Değişiklikleri kaydet' }).hasAttribute('disabled')).toBe(true);
});

test('invalid range prevents save; server error retains draft; refresh does not overwrite it', async () => {
  const { client } = mount(); await choose();
  fireEvent.change(screen.getByLabelText('Pazartesi başlangıç'), { target: { value: '19:00' } });
  fireEvent.click(screen.getByRole('button', { name: 'Değişiklikleri kaydet' }));
  await screen.findByRole('alert'); expect(api.saveWorkingHours).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('Pazartesi başlangıç'), { target: { value: '11:00' } });
  vi.mocked(api.saveWorkingHours).mockRejectedValue(new Error('Offline'));
  fireEvent.click(screen.getByRole('button', { name: 'Değişiklikleri kaydet' }));
  await screen.findByText('Çalışma saatleri kaydedilemedi. Değişiklikleriniz korundu.');
  vi.mocked(api.getWorkingHours).mockResolvedValue([{ ...monday, startTime: '08:00:00' }]);
  await act(async () => { await client.refetchQueries({ queryKey: queryKeys.workingHours('tenant', 'one') }); });
  expect((screen.getByLabelText('Pazartesi başlangıç') as HTMLInputElement).value).toBe('11:00');
});

test('dirty staff switch and route navigation require discard confirmation', async () => {
  mount(); await choose();
  fireEvent.click(screen.getByLabelText('Salı açık'));
  fireEvent.change(screen.getByLabelText('Personel'), { target: { value: 'two' } });
  await screen.findByRole('heading', { name: 'Personeli değiştir' });
  fireEvent.click(screen.getByRole('button', { name: 'Vazgeç' }));
  expect((screen.getByLabelText('Personel') as HTMLSelectElement).value).toBe('one');
  fireEvent.click(screen.getByRole('link', { name: 'Başka sayfa' }));
  await screen.findByRole('heading', { name: 'Kaydedilmemiş değişiklikler' });
  fireEvent.click(screen.getByRole('button', { name: 'Düzenlemeye dön' }));
  expect((screen.getByLabelText('Salı açık') as HTMLInputElement).checked).toBe(true);
  fireEvent.click(screen.getByRole('link', { name: 'Başka sayfa' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Kaydetmeden ayrıl' }));
  await screen.findByRole('heading', { name: 'Diğer sayfa' });
});

test('pending save disables staff switch and duplicate save', async () => {
  const pending = deferred<WorkingHours[]>(); vi.mocked(api.saveWorkingHours).mockReturnValue(pending.promise);
  mount(); await choose(); fireEvent.click(screen.getByLabelText('Pazartesi açık'));
  fireEvent.click(screen.getByRole('button', { name: 'Değişiklikleri kaydet' }));
  await waitFor(() => expect(screen.getByLabelText('Personel').hasAttribute('disabled')).toBe(true));
  expect(screen.getByRole('button', { name: 'Kaydediliyor…' }).hasAttribute('disabled')).toBe(true);
  await act(async () => { pending.resolve([]); });
  await screen.findByText('Çalışma saatleri kaydedildi.');
  expect(api.saveWorkingHours).toHaveBeenCalledOnce();
});

test('missing staff gives an actionable empty state; a loading failure is not an empty week', async () => {
  vi.mocked(api.getStaff).mockResolvedValue([]); mount();
  await screen.findByRole('heading', { name: 'Önce personel ekleyin' });
  expect(api.getWorkingHours).not.toHaveBeenCalled(); cleanup();
  vi.mocked(api.getStaff).mockResolvedValue([{ id: 'one', displayName: 'Deniz', active: true }]);
  vi.mocked(api.getWorkingHours).mockRejectedValue(new Error('Offline')); mount();
  await screen.findByLabelText('Personel'); fireEvent.change(screen.getByLabelText('Personel'), { target: { value: 'one' } });
  await screen.findByRole('alert'); expect(screen.queryByLabelText('Pazartesi açık')).toBeNull();
  vi.mocked(api.getWorkingHours).mockResolvedValue([]); fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }));
  await screen.findByLabelText('Pazartesi açık');
});

test('time normalization preserves seconds and closed days omit times', () => {
  expect(weekPayload(fromHours([{ ...monday, startTime: '09:00:30' }]))[0].startTime).toBe('09:00:30');
  expect(weekErrors([{ dayOfWeek: 0, closed: false, startTime: '18:00', endTime: '18:00:00' }])[0]).toBeTruthy();
});
