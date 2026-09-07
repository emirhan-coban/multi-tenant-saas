export const queryKeys = {
  me: ['session', 'me'] as const,
  tenant: (tenantId: string) => ['tenant', tenantId] as const,
  services: (tenantId: string) => ['tenant', tenantId, 'services'] as const,
  staff: (tenantId: string) => ['tenant', tenantId, 'staff'] as const,
  workingHours: (tenantId: string, personelId: string) =>
    ['tenant', tenantId, 'working-hours', personelId] as const,
};
