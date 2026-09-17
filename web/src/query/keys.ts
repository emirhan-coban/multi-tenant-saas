export const queryKeys = {
  me: ['session', 'me'] as const,
  tenant: (tenantId: string) => ['tenant', tenantId] as const,
  bookingLink: (tenantId: string) => ['tenant', tenantId, 'booking-link'] as const,
  services: (tenantId: string) => ['tenant', tenantId, 'services'] as const,
  staff: (tenantId: string) => ['tenant', tenantId, 'staff'] as const,
  workingHours: (tenantId: string, personelId: string) =>
    ['tenant', tenantId, 'working-hours', personelId] as const,
  bookingRequests: (tenantId: string, status: string) =>
    ['tenant', tenantId, 'booking-requests', status] as const,
  bookingCustomers: (tenantId: string) => ['tenant', tenantId, 'booking-customers'] as const,
  customerTrustEvents: (tenantId: string, customerId: string) =>
    ['tenant', tenantId, 'booking-customers', customerId, 'trust-events'] as const,
  rescheduleRequests: (tenantId: string) => ['tenant', tenantId, 'reschedule-requests'] as const,
  bookingSettings: (tenantId: string) => ['tenant', tenantId, 'booking-settings'] as const,
  shopDesign: (tenantId: string) => ['tenant', tenantId, 'shop-design'] as const,
};
