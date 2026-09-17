export const customerTrustStates = ['NEW', 'TRUSTED', 'REVIEW_REQUIRED', 'BLOCKED'] as const;
export type CustomerTrustState = typeof customerTrustStates[number];

export const bookingRequestStatuses = [
  'PENDING_APPROVAL',
  'AUTO_CONFIRMED',
  'APPROVED',
  'REJECTED',
  'EXPIRED',
  'CANCELLED',
] as const;
export type BookingRequestStatus = typeof bookingRequestStatuses[number];

export type BookingDecision = 'REQUIRE_APPROVAL' | 'AUTO_CONFIRM' | 'REJECT';
export type BookingResultType = 'REQUEST' | 'APPOINTMENT';

export const bookingDefaults = {
  bookingHorizonDays: 30,
  minimumLeadMinutes: 60,
  approvalHoldMinutes: 15,
  customerCancellationMinutes: 60,
  maxActiveRequestsPerCustomer: 1,
  maxRequestsPerIpPerHour: 5,
  maxFutureAppointments: 2,
  maxDailyBookings: 3,
} as const;

export type BookingRequest = {
  id: string;
  tenantSlug: string;
  resultType: BookingResultType;
  serviceId: string;
  staffId: string;
  startsAt: string;
  status: BookingRequestStatus;
  trustState: CustomerTrustState;
  decision: BookingDecision;
  holdExpiresAt: string | null;
  appointmentId: string | null;
  canCancel: boolean;
  cancellableUntil: string | null;
  version: number;
  appointmentVersion: number | null;
  canReschedule: boolean;
  reschedulableUntil: string | null;
};

export type AppointmentStatus = 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';

export type CustomerBooking = BookingRequest & {
  tenantName: string;
  timezone: string;
  serviceName: string;
  staffName: string;
  busyUntil: string;
  appointmentStatus: AppointmentStatus | null;
  rejectionReason: string | null;
  createdAt: string;
  cancelledAt: string | null;
  rescheduleRequestId: string | null;
  rescheduleStatus: 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'NEEDS_RESELECTION' | 'CANCELLED' | null;
  proposedStartsAt: string | null;
};

export type InAppNotification = {
  id: string; tenantId: string; eventType: string; title: string; body: string;
  relatedType: string | null; relatedId: string | null; readAt: string | null; createdAt: string;
};

export type CustomerBookings = {
  items: CustomerBooking[];
  summary: { pending: number; upcoming: number; completed: number; cancelled: number; noShow: number };
};
