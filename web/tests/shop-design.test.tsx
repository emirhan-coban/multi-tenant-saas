import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { TenantProvider } from '../src/store/tenant';
import { ShopDesignScreen } from '../src/screens/ShopDesignScreen';
import * as management from '../src/api/management';

vi.mock('../src/components/BarberShopScene', () => ({ BarberShopScene: () => <div data-testid="scene-preview">3D preview</div> }));
vi.mock('../src/api/management', () => ({ getShopDesign: vi.fn(), saveShopDesign: vi.fn(), publishShopDesign: vi.fn(), rollbackShopDesign: vi.fn() }));

const chair = { chairKey: 'chair-1', label: 'Pencere', staffId: 'staff-1', staffName: 'Deniz Usta', positionX: 0, positionZ: -1.25, rotationY: 0 };
const state = {
  draft: { version: 2, status: 'DRAFT' as const, templateKey: 'CLASSIC_3_CHAIR' as const, accentColor: '#245e50', revision: 0, publishedAt: null, chairs: [chair] },
  published: { version: 1, status: 'PUBLISHED' as const, templateKey: 'CLASSIC_3_CHAIR' as const, accentColor: '#245e50', revision: 1, publishedAt: '2030-01-01T00:00:00Z', chairs: [chair] },
  history: [1], staff: [{ id: 'staff-1', displayName: 'Deniz Usta' }],
};

beforeEach(() => {
  vi.mocked(management.getShopDesign).mockResolvedValue(state);
  vi.mocked(management.saveShopDesign).mockResolvedValue({ ...state, draft: { ...state.draft, revision: 1 } });
  vi.mocked(management.publishShopDesign).mockResolvedValue(state);
});

test('publish saves the visible draft before publishing its returned revision', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><TenantProvider user={{ userId: 'owner', tenantId: 'tenant', role: 'OWNER' }}><ShopDesignScreen /></TenantProvider></QueryClientProvider>);
  fireEvent.click(await screen.findByRole('button', { name: 'Renk #7a4034' }));
  fireEvent.click(screen.getByRole('button', { name: 'Yayınla' }));
  await waitFor(() => expect(management.saveShopDesign).toHaveBeenCalledWith(expect.objectContaining({ accentColor: '#7a4034' }), expect.anything()));
  await waitFor(() => expect(management.publishShopDesign).toHaveBeenCalledWith(1, expect.anything()));
});
afterEach(cleanup);

test('manager edits and saves the versioned chair mapping', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><TenantProvider user={{ userId: 'owner', tenantId: 'tenant', role: 'OWNER' }}><ShopDesignScreen /></TenantProvider></QueryClientProvider>);
  expect(await screen.findByTestId('scene-preview')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Etiket'), { target: { value: 'Kapı yanı' } });
  fireEvent.click(screen.getByRole('button', { name: 'Taslağı kaydet' }));
  await waitFor(() => expect(management.saveShopDesign).toHaveBeenCalledWith(expect.objectContaining({
    version: 2, revision: 0, chairs: [expect.objectContaining({ label: 'Kapı yanı', staffId: 'staff-1' })],
  }), expect.anything()));
  expect(await screen.findByText('Taslak kaydedildi.')).toBeTruthy();
});

test('owner can add a chair for an unmapped barber and remove it again', async () => {
  vi.mocked(management.getShopDesign).mockResolvedValue({ ...state, staff: [...state.staff, { id: 'staff-2', displayName: 'Mert Berber' }] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><TenantProvider user={{ userId: 'owner', tenantId: 'tenant', role: 'OWNER' }}><ShopDesignScreen /></TenantProvider></QueryClientProvider>);
  await screen.findByTestId('scene-preview');
  expect(screen.getAllByText('Koltuğu kaldır')).toHaveLength(1);
  fireEvent.click(screen.getByRole('button', { name: '+ Koltuk ekle' }));
  expect(screen.getByText('2. koltuk')).toBeTruthy();
  expect(screen.getByRole('button', { name: '+ Koltuk ekle' }).hasAttribute('disabled')).toBe(true);
  fireEvent.click(screen.getAllByText('Koltuğu kaldır')[1]);
  expect(screen.queryByText('2. koltuk')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '+ Koltuk ekle' }));
  fireEvent.click(screen.getByRole('button', { name: 'Taslağı kaydet' }));
  await waitFor(() => expect(management.saveShopDesign).toHaveBeenCalledWith(expect.objectContaining({
    chairs: [expect.objectContaining({ chairKey: 'chair-1' }), expect.objectContaining({ chairKey: 'chair-2', staffId: 'staff-2', positionX: 0 })],
  }), expect.anything()));
});

test('empty chair list guides the owner and blocks publishing', async () => {
  vi.mocked(management.getShopDesign).mockResolvedValue({ ...state, draft: { ...state.draft, chairs: [] }, staff: [] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><TenantProvider user={{ userId: 'owner', tenantId: 'tenant', role: 'OWNER' }}><ShopDesignScreen /></TenantProvider></QueryClientProvider>);
  expect(await screen.findByText(/Henüz koltuk yok/)).toBeTruthy();
  expect(screen.getByText('Önce Personel ekranından berber ekleyin.')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Yayınla' }).hasAttribute('disabled')).toBe(true);
});
