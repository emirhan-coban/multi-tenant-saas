import { createContext, useContext, type ReactNode } from 'react';
import type { CurrentUser } from '../api/types';

const TenantContext = createContext<CurrentUser | null>(null);

export function TenantProvider({ user, children }: { user: CurrentUser; children: ReactNode }) {
  return <TenantContext.Provider value={user}>{children}</TenantContext.Provider>;
}

// Only mounted after /api/me succeeds; caller cannot pick an arbitrary tenant key.
export function useTenant() {
  const user = useContext(TenantContext);
  if (!user) throw new Error('useTenant requires a verified tenant session');
  return user;
}
