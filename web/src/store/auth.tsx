import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, getRefreshToken, persistRefreshToken, setAccessToken } from '../api/client';

type Membership = { tenantId: string; tenantName: string; role: string };
type AuthData = { accessToken: string; refreshToken: string; memberships: Membership[] };
type AuthContextValue = { ready: boolean; session: AuthData | null; login: (values: object) => Promise<void>; register: (values: object) => Promise<void>; logout: () => void };
const AuthContext = createContext<AuthContextValue | null>(null);

function applySession(data: AuthData) { setAccessToken(data.accessToken); persistRefreshToken(data.refreshToken); return data; }

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AuthData | null>(null);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const refreshToken = getRefreshToken();
    if (!refreshToken) { setReady(true); return; }
    api.post('/api/auth/refresh', { refreshToken }).then(({ data }) => setSession(applySession(data))).catch(() => persistRefreshToken(null)).finally(() => setReady(true));
  }, []);
  async function authenticate(path: string, values: object) { const { data } = await api.post<AuthData>(path, values); setSession(applySession(data)); }
  function logout() { setSession(null); setAccessToken(null); persistRefreshToken(null); }
  return <AuthContext.Provider value={{ ready, session, login: values => authenticate('/api/auth/login', values), register: values => authenticate('/api/auth/register', values), logout }}>{children}</AuthContext.Provider>;
}
export function useAuth() { const context = useContext(AuthContext); if (!context) throw new Error('useAuth must be used within AuthProvider'); return context; }