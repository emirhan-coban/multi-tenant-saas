import { describe, expect, test } from 'vitest';
import {
  bookingDefaults,
  bookingRequestStatuses,
  customerTrustStates,
  type BookingRequest,
} from '../src/api/booking-contract';

const base = {
  id: '10000000-0000-0000-0000-000000000001',
  tenantSlug: 'ornek-berber',
  serviceId: '20000000-0000-0000-0000-000000000001',
  staffId: '30000000-0000-0000-0000-000000000001',
  startsAt: '2030-01-07T09:00:00Z',
  appointmentId: null,
  canCancel: true,
  cancellableUntil: '2030-01-07T08:00:00Z',
  version: 1,
  appointmentVersion: 1,
  canReschedule: true,
  reschedulableUntil: '2030-01-07T08:00:00Z',
};

const fixtures: Record<'newCustomer' | 'trustedCustomer' | 'reviewRequired', BookingRequest> = {
  newCustomer: { ...base, resultType: 'REQUEST', status: 'PENDING_APPROVAL', trustState: 'NEW', decision: 'REQUIRE_APPROVAL', holdExpiresAt: '2030-01-07T08:15:00Z' },
  trustedCustomer: { ...base, resultType: 'APPOINTMENT', status: 'AUTO_CONFIRMED', trustState: 'TRUSTED', decision: 'AUTO_CONFIRM', holdExpiresAt: null, appointmentId: '40000000-0000-0000-0000-000000000001' },
  reviewRequired: { ...base, resultType: 'REQUEST', status: 'PENDING_APPROVAL', trustState: 'REVIEW_REQUIRED', decision: 'REQUIRE_APPROVAL', holdExpiresAt: '2030-01-07T08:15:00Z' },
};

describe('booking v1 contract', () => {
  test('freezes public enum values', () => {
    expect(customerTrustStates).toEqual(['NEW', 'TRUSTED', 'REVIEW_REQUIRED', 'BLOCKED']);
    expect(bookingRequestStatuses).toEqual(['PENDING_APPROVAL', 'AUTO_CONFIRMED', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED']);
  });

  test('maps NEW, TRUSTED and REVIEW_REQUIRED fixtures to the decision table', () => {
    expect(fixtures.newCustomer.decision).toBe('REQUIRE_APPROVAL');
    expect(fixtures.trustedCustomer.decision).toBe('AUTO_CONFIRM');
    expect(fixtures.reviewRequired.decision).toBe('REQUIRE_APPROVAL');
  });

  test('keeps scheduling and abuse defaults aligned with backend', () => {
    expect(bookingDefaults).toEqual({
      bookingHorizonDays: 30,
      minimumLeadMinutes: 60,
      approvalHoldMinutes: 15,
      customerCancellationMinutes: 60,
      maxActiveRequestsPerCustomer: 1,
      maxRequestsPerIpPerHour: 5,
      maxFutureAppointments: 2,
      maxDailyBookings: 3,
    });
  });
});
