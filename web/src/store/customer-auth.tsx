import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import {
  authenticateCustomer,
  resetCustomerSession,
  restoreCustomerSession,
  subscribeCustomerSession,
  type CustomerAuthData,
} from '../api/customer-client';

type CustomerAuthContextValue = {
  ready: boolean;
  session: CustomerAuthData | null;
  login: (values: object) => Promise<void>;
  register: (values: object) => Promise<void>;
  logout: () => void;
};

const CustomerAuthContext = createContext<CustomerAuthContextValue | null>(null);

export function CustomerAuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<CustomerAuthData | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let active = true;
    const unsubscribe = subscribeCustomerSession(data => setSession(data));
    void restoreCustomerSession().then(data => {
      if (active) {
        setSession(data);
        setReady(true);
      }
    });
    return () => {
      active = false;
      unsubscribe();
    };
  }, []);

  function logout() {
    resetCustomerSession();
    setSession(null);
  }

  async function authenticate(mode: 'login' | 'register', values: object) {
    logout();
    const data = await authenticateCustomer(mode, values);
    setSession(data);
  }

  return <CustomerAuthContext.Provider value={{
    ready,
    session,
    logout,
    login: values => authenticate('login', values),
    register: values => authenticate('register', values),
  }}>{children}</CustomerAuthContext.Provider>;
}

export function useCustomerAuth() {
  const value = useContext(CustomerAuthContext);
  if (!value) throw new Error('useCustomerAuth must be used within CustomerAuthProvider');
  return value;
}
