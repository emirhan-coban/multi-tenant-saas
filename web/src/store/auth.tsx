import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authenticate, resetSessionTransport, restoreSession, subscribeSession, switchTenantRequest } from '../api/client';
import type { AuthData } from '../api/types';
import { clearSessionQueries } from '../query/client';

type AuthContextValue = {
  ready: boolean;
  session: AuthData | null;
  login: (values: object) => Promise<void>;
  register: (values: object) => Promise<void>;
  logout: () => void;
  switchTenant: (tenantId: string) => Promise<void>;
};
const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [session, setSession] = useState<AuthData | null>(null);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    let active = true;
    const unsubscribe = subscribeSession(data => {
      if (!data) clearSessionQueries(queryClient);
      else void queryClient.invalidateQueries({ queryKey: ['session', 'me'] });
      setSession(data);
    });
    // Shared promise also deduplicates React StrictMode's initial effect replay.
    void restoreSession().then(data => {
      if (active) { setSession(data); setReady(true); }
    });
    return () => { active = false; unsubscribe(); };
  }, [queryClient]);

  function logout() {
    resetSessionTransport();
    clearSessionQueries(queryClient);
    setSession(null);
  }
  async function signIn(path: '/api/auth/login' | '/api/auth/register', values: object) {
    logout();
    const data = await authenticate(path, values);
    setSession(data);
  }
  async function switchTenant(tenantId: string) {
    const data = await switchTenantRequest(tenantId);
    clearSessionQueries(queryClient);
    setSession(data);
  }
  return <AuthContext.Provider value={{
    ready, session, logout, switchTenant,
    login: values => signIn('/api/auth/login', values),
    register: values => signIn('/api/auth/register', values),
  }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}
