export type Role = 'OWNER' | 'MANAGER' | 'STAFF';
export type Membership = { tenantId: string; tenantName: string; role: Role };
export type AuthData = { accessToken: string; refreshToken: string; memberships: Membership[] };
export type CurrentUser = { userId: string; tenantId: string; role: Role };
export type ServiceInput = { name: string; durationMin: number; price: number; bufferMin?: number };
export type Service = ServiceInput & { id: string; bufferMin: number };
export type Staff = { id: string; displayName: string; active: boolean };
export type InviteInput = { email: string; displayName: string };
export type InviteResult = { personelId: string; email: string; geciciSifre: string | null };
export type WorkingHours = {
  id: string;
  personelId: string;
  dayOfWeek: number;
  startTime: string;
  endTime: string;
};
export type ApiError = { code: string; message: string; details?: Record<string, unknown> };
export type WorkingDay = { dayOfWeek: number; closed: boolean; startTime: string | null; endTime: string | null };
export type WeeklyHoursInput = { personelId: string; days: WorkingDay[] };
