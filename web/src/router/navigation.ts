import type { Role } from '../api/types';

export const memberRoles: readonly Role[] = ['OWNER', 'MANAGER', 'STAFF'];
export const managementRoles: readonly Role[] = ['OWNER', 'MANAGER'];
export const ownerRoles: readonly Role[] = ['OWNER'];
export const roleLabels: Record<Role, string> = { OWNER: 'İşletme sahibi', MANAGER: 'Yönetici', STAFF: 'Personel' };

export const navigation = [
  { path: '/app', label: 'Genel Bakış', roles: memberRoles, mark: '01' },
  { path: '/app/booking-link', label: 'Rezervasyon Bağlantım', roles: memberRoles, mark: '12' },
  { path: '/app/services', label: 'Hizmetler', roles: managementRoles, mark: '02' },
  { path: '/app/staff', label: 'Personel', roles: managementRoles, mark: '03' },
  { path: '/app/working-hours', label: 'Çalışma Saatleri', roles: managementRoles, mark: '04' },
  { path: '/app/appointments', label: 'Randevular', roles: managementRoles, mark: '06' },
  { path: '/app/booking-requests', label: 'Onay Bekleyenler', roles: managementRoles, mark: '11' },
  { path: '/app/booking-settings', label: 'Rezervasyon Ayarları', roles: managementRoles, mark: '14' },
  { path: '/app/shop-design', label: 'Dükkân Tasarımı', roles: managementRoles, mark: '15' },
  { path: '/app/customers', label: 'Müşteriler', roles: managementRoles, mark: '13' },
  { path: '/app/account', label: 'Hesabım', roles: memberRoles, mark: '05' },
  { path: '/app/reports', label: 'Raporlar', roles: managementRoles, mark: '07' },
  { path: '/app/audit-log', label: 'İşlem Geçmişi', roles: managementRoles, mark: '08' },
  { path: '/app/plan', label: 'Plan ve Kullanım', roles: managementRoles, mark: '09' },
  { path: '/app/notifications', label: 'Bildirimler', roles: memberRoles, mark: '10' },
  { path: '/app/feature-flags', label: 'Özellik Anahtarları', roles: ownerRoles, mark: '16' },
] as const;

export function canAccess(role: string, allowed: readonly Role[]) {
  return allowed.some(candidate => candidate === role);
}
