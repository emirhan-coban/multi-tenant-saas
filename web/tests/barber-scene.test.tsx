import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';

vi.mock('@react-three/fiber', () => ({ Canvas: () => { throw new Error('WebGL unavailable'); } }));
vi.mock('@react-three/drei', () => {
  const useGLTF = Object.assign(vi.fn(), { preload: vi.fn() });
  return { ContactShadows: () => null, OrbitControls: () => null, useGLTF };
});

import { BarberShopScene } from '../src/components/BarberShopScene';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

test('WebGL failure exposes the list fallback without losing the selection callback', async () => {
  const failed = vi.fn();
  const manifest = { version: 1, templateKey: 'CLASSIC_3_CHAIR' as const, accentColor: '#245e50', chairs: [
    { chairKey: 'chair-1', label: 'Koltuk 1', staffId: 'staff-1', staffName: 'Deniz', positionX: 0, positionZ: -1, rotationY: 0 },
  ] };
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  render(<BarberShopScene manifest={manifest} selectedId="staff-1" onSelect={vi.fn()} onFailure={failed} />);
  expect(await screen.findByText('3D görünüm açılamadı. Liste görünümüne geçiliyor.')).toBeTruthy();
  await waitFor(() => expect(failed).toHaveBeenCalledOnce());
});
