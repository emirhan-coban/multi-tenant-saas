import type { Role } from '../api/types';

export const memberRoles: readonly Role[] = ['OWNER', 'MANAGER', 'STAFF'];
export const managementRoles: readonly Role[] = ['OWNER', 'MANAGER'];
export const roleLabels: Record<Role, string> = { OWNER: 'İşletme sahibi', MANAGER: 'Yönetici', STAFF: 'Personel' };

export const navigation = [
  { path: '/app', label: 'Genel Bakış', roles: memberRoles, mark: '01' },
  { path: '/app/services', label: 'Hizmetler', roles: managementRoles, mark: '02' },
  { path: '/app/staff', label: 'Personel', roles: managementRoles, mark: '03' },
  { path: '/app/working-hours', label: 'Çalışma Saatleri', roles: managementRoles, mark: '04' },
  { path: '/app/appointments', label: 'Randevular', roles: managementRoles, mark: '06' },
  { path: '/app/account', label: 'Hesabım', roles: memberRoles, mark: '05' },
] as const;

export function canAccess(role: string, allowed: readonly Role[]) {
  return allowed.some(candidate => candidate === role);
}
