export type Role = 'OWNER' | 'MANAGER' | 'STAFF';
export type Membership = { tenantId: string; tenantName: string; role: Role };
export type AuthData = { accessToken: string; refreshToken: string; memberships: Membership[] };
export type CurrentUser = { userId: string; tenantId: string; role: Role };
export type BookingLink = { tenantId: string; tenantName: string; tenantSlug: string; timezone: string };
export type ServiceInput = { name: string; durationMin: number; price: number; bufferMin?: number };
export type Service = ServiceInput & { id: string; bufferMin: number };
export type Staff = { id: string; displayName: string; active: boolean };
export type InviteInput = { email: string; displayName: string };
export type InviteResult = { personelId: string; email: string; invitationSent: boolean };
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
export type BookingApprovalRequest = {
  id: string;
  customerId: string;
  customerName: string;
  customerEmail: string;
  customerPhone: string | null;
  trustState: import('./booking-contract').CustomerTrustState;
  completedCount: number;
  noShowCount: number;
  trustVersion: number;
  serviceId: string;
  serviceName: string;
  staffId: string;
  staffName: string;
  startsAt: string;
  busyUntil: string;
  status: import('./booking-contract').BookingRequestStatus;
  decision: import('./booking-contract').BookingDecision;
  holdExpiresAt: string | null;
  appointmentId: string | null;
  note: string | null;
  rejectionReason: string | null;
  createdAt: string;
  decidedAt: string | null;
};
export type CustomerTrust = Pick<BookingApprovalRequest, 'customerId' | 'trustState' | 'completedCount' | 'noShowCount'> & {
  blockedReason: string | null;
  trustedAt: string | null;
  trustReason: string | null;
  evidenceAppointmentId: string | null;
  version: number;
  updatedAt: string;
};
export type BookingCustomer = {
  customerId: string; customerName: string; customerEmail: string; customerPhone: string | null;
  trustState: import('./booking-contract').CustomerTrustState; completedCount: number; noShowCount: number;
  blockedReason: string | null; trustedAt: string | null; trustReason: string | null;
  evidenceAppointmentId: string | null; version: number; lastAppointmentAt: string | null;
};
export type CustomerTrustEvent = {
  id: string; fromState: import('./booking-contract').CustomerTrustState;
  toState: import('./booking-contract').CustomerTrustState; reason: string; actorName: string;
  evidenceAppointmentId: string | null; createdAt: string;
};
export type StaffRescheduleRequest = {
  id: string; bookingRequestId: string; appointmentId: string; customerId: string;
  customerName: string; customerEmail: string; staffId: string; staffName: string;
  serviceId: string; serviceName: string; currentStartsAt: string; requestedStartsAt: string;
  status: 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'NEEDS_RESELECTION' | 'CANCELLED';
  appointmentVersion: number; rejectionReason: string | null; createdAt: string;
  decidedAt: string | null; version: number;
};
export type BookingSettings = {
  trustedAutoConfirmEnabled: boolean; maxFutureAppointments: number; maxDailyBookings: number;
  bookingHorizonDays: number; minimumLeadMinutes: number; requestExpiryMinutes: number;
  cancellationCutoffMinutes: number; version: number; updatedAt: string;
};
