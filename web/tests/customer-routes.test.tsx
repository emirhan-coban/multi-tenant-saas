import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { AppRoutes } from '../src/router/AppRoutes';
import { CustomerAuthProvider } from '../src/store/customer-auth';
import {
  authenticateCustomer,
  resetCustomerSession,
  restoreCustomerSession,
} from '../src/api/customer-client';

vi.mock('../src/api/customer-client', () => ({
  customerApi: { get: vi.fn() },
  authenticateCustomer: vi.fn(),
  resetCustomerSession: vi.fn(),
  restoreCustomerSession: vi.fn(),
  subscribeCustomerSession: () => () => {},
}));

const session = {
  accessToken: 'customer-access',
  refreshToken: 'customer-refresh',
  customer: { id: 'customer-1', email: 'ada@example.com', fullName: 'Ada Lovelace', phone: null },
};

function Location() { return <output data-testid="location">{useLocation().pathname}</output>; }
function mount(path: string) {
  render(<CustomerAuthProvider><MemoryRouter initialEntries={[path]}><AppRoutes /><Location /></MemoryRouter></CustomerAuthProvider>);
}

beforeEach(() => {
  vi.mocked(restoreCustomerSession).mockReset();
  vi.mocked(authenticateCustomer).mockReset();
  vi.mocked(resetCustomerSession).mockReset();
});
afterEach(cleanup);

test('customer account deep link redirects to the separate customer login', async () => {
  vi.mocked(restoreCustomerSession).mockResolvedValue(null);
  mount('/customer/account');
  await screen.findByRole('heading', { name: 'Müşteri girişi' });
  expect(screen.getByTestId('location').textContent).toBe('/customer/login');
});

test('restored customer session opens the account without staff auth', async () => {
  vi.mocked(restoreCustomerSession).mockResolvedValue(session);
  mount('/customer/account');
  await screen.findByRole('heading', { name: 'Merhaba, Ada Lovelace' });
  expect(screen.getByText('ada@example.com')).toBeTruthy();
});

test('new customer can register and is redirected to the account', async () => {
  vi.mocked(restoreCustomerSession).mockResolvedValue(null);
  vi.mocked(authenticateCustomer).mockResolvedValue(session);
  mount('/customer/login');
  await screen.findByRole('heading', { name: 'Müşteri girişi' });
  fireEvent.click(screen.getByRole('button', { name: 'İlk kez mi geliyorsun? Hesap oluştur' }));
  fireEvent.change(screen.getByLabelText('Ad soyad'), { target: { value: 'Ada Lovelace' } });
  fireEvent.change(screen.getByLabelText('E-posta'), { target: { value: 'ada@example.com' } });
  fireEvent.change(screen.getByLabelText('Şifre'), { target: { value: 'secret123' } });
  fireEvent.click(screen.getByRole('button', { name: 'Hesap oluştur' }));
  await waitFor(() => expect(authenticateCustomer).toHaveBeenCalledWith('register', expect.objectContaining({ email: 'ada@example.com' })));
  await screen.findByRole('heading', { name: 'Merhaba, Ada Lovelace' });
  expect(screen.getByTestId('location').textContent).toBe('/customer/account');
});
