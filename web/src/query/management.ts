import { CanceledError } from 'axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as management from '../api/management';
import type { BookingApprovalRequest, InviteInput, InviteResult, Service, StaffRescheduleRequest } from '../api/types';
import type { BookingRequestStatus } from '../api/booking-contract';
import { useAuth } from '../store/auth';
import { useTenant } from '../store/tenant';
import { queryKeys } from './keys';

export function useCurrentUser() {
  const { ready, session } = useAuth();
  return useQuery({
    queryKey: queryKeys.me,
    queryFn: ({ signal }) => management.getMe(signal),
    enabled: ready && !!session,
  });
}

export function useBookingLink() {
  const { tenantId } = useTenant();
  return useQuery({
    queryKey: queryKeys.bookingLink(tenantId),
    queryFn: ({ signal }) => management.getBookingLink(signal),
  });
}

export type ServiceRow = Service & { optimistic?: boolean };

export function useServices() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  const key = queryKeys.services(tenantId);
  return useQuery({
    queryKey: key,
    queryFn: async ({ signal }): Promise<ServiceRow[]> => {
      const services = await management.getServices(signal);
      // A focus refresh or another mutation must not hide still-pending additions.
      const pending = client.getQueryData<ServiceRow[]>(key)?.filter(row => row.optimistic) ?? [];
      return [...pending, ...services];
    },
  });
}
export function useCreateService() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  const key = queryKeys.services(tenantId);
  return useMutation({
    mutationFn: management.createService,
    onMutate: async input => {
      const query = client.getQueryCache().find({ queryKey: key, exact: true });
      await client.cancelQueries({ queryKey: key });
      // Logout can clear the cache during this await; do not start a new request then.
      if (!query || client.getQueryCache().find({ queryKey: key, exact: true }) !== query) throw new CanceledError();
      const id = `pending-${crypto.randomUUID()}`;
      client.setQueryData<ServiceRow[]>(key, rows => [
        { ...input, id, bufferMin: input.bufferMin ?? 0, optimistic: true }, ...(rows ?? []),
      ]);
      return { id };
    },
    onSuccess: async (service, _input, context) => {
      await client.cancelQueries({ queryKey: key });
      client.setQueryData<ServiceRow[]>(key, rows => rows?.map(row => row.id === context.id ? service : row));
    },
    onError: (_error, _input, context) => {
      // Roll back only this request, preserving concurrent successful additions.
      client.setQueryData<ServiceRow[]>(key, rows => rows?.filter(row => row.id !== context?.id));
    },
    onSettled: () => { void client.invalidateQueries({ queryKey: key }); },
  });
}
export function useUpdateService() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  const key = queryKeys.services(tenantId);
  return useMutation({
    mutationFn: management.updateService,
    onSuccess: async service => {
      await client.cancelQueries({ queryKey: key });
      client.setQueryData<ServiceRow[]>(key, rows => rows?.map(row => row.id === service.id ? service : row));
      void client.invalidateQueries({ queryKey: key });
    },
  });
}
export function useDeleteService() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  const key = queryKeys.services(tenantId);
  return useMutation({
    mutationFn: management.deleteService,
    onSuccess: async (_result, id) => {
      await client.cancelQueries({ queryKey: key });
      client.setQueryData<ServiceRow[]>(key, rows => rows?.filter(row => row.id !== id));
      void client.invalidateQueries({ queryKey: key });
    },
  });
}
export function useStaff() {
  const { tenantId } = useTenant();
  return useQuery({ queryKey: queryKeys.staff(tenantId), queryFn: ({ signal }) => management.getStaff(signal) });
}
// Deliver the temporary password to the form without retaining it in MutationCache.
// The form owns the result and must discard it when the result dialog closes.
export function useInviteStaff(onResult: (result: InviteResult) => void) {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  return useMutation({
    gcTime: 0,
    mutationFn: async (input: InviteInput): Promise<void> => {
      const result = await management.inviteStaff(input);
      onResult(result);
    },
    onSuccess: () => { void client.invalidateQueries({ queryKey: queryKeys.staff(tenantId) }); },
  });
}
export function useWorkingHours(personelId: string | null) {
  const { tenantId } = useTenant();
  return useQuery({
    queryKey: queryKeys.workingHours(tenantId, personelId ?? ''),
    queryFn: ({ signal }) => {
      if (!personelId) throw new Error('Personel seçilmedi');
      return management.getWorkingHours(personelId, signal);
    },
    enabled: !!personelId,
  });
}

export function useSaveWorkingHours() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  return useMutation({
    mutationFn: management.saveWorkingHours,
    onSuccess: async (rows, input) => {
      const key = queryKeys.workingHours(tenantId, input.personelId);
      const query = client.getQueryCache().find({ queryKey: key, exact: true });
      await client.cancelQueries({ queryKey: key });
      if (query && client.getQueryCache().find({ queryKey: key, exact: true }) === query) client.setQueryData(key, rows);
    },
  });
}

export function useBookingRequests(status: BookingRequestStatus = 'PENDING_APPROVAL') {
  const { tenantId } = useTenant();
  return useQuery({
    queryKey: queryKeys.bookingRequests(tenantId, status),
    queryFn: ({ signal }) => management.getBookingRequests(status, signal),
    refetchInterval: status === 'PENDING_APPROVAL' ? 30_000 : false,
  });
}

function useBookingDecision(action: 'approve' | 'reject') {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  return useMutation({
    mutationFn: action === 'approve'
      ? (input: { id: string; reason?: string }) => management.approveBookingRequest(input.id)
      : management.rejectBookingRequest,
    onSuccess: (result: BookingApprovalRequest) => {
      client.setQueryData<BookingApprovalRequest[]>(queryKeys.bookingRequests(tenantId, 'PENDING_APPROVAL'),
        rows => rows?.filter(row => row.id !== result.id));
      void client.invalidateQueries({ queryKey: ['tenant', tenantId, 'booking-requests'] });
    },
  });
}

export function useApproveBookingRequest() { return useBookingDecision('approve'); }
export function useRejectBookingRequest() { return useBookingDecision('reject'); }

export function useUpdateCustomerTrust() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  return useMutation({
    mutationFn: management.updateCustomerTrust,
    onSuccess: result => {
      client.setQueriesData<BookingApprovalRequest[]>({ queryKey: ['tenant', tenantId, 'booking-requests'] }, rows =>
        rows?.map(row => row.customerId === result.customerId ? { ...row, trustState: result.trustState, trustVersion: result.version } : row));
      void client.invalidateQueries({ queryKey: queryKeys.bookingCustomers(tenantId) });
      void client.invalidateQueries({ queryKey: queryKeys.customerTrustEvents(tenantId, result.customerId) });
    },
  });
}

export function useBookingCustomers() {
  const { tenantId } = useTenant();
  return useQuery({ queryKey: queryKeys.bookingCustomers(tenantId), queryFn: ({ signal }) => management.getBookingCustomers(signal) });
}

export function useCustomerTrustEvents(customerId: string | null) {
  const { tenantId } = useTenant();
  return useQuery({
    queryKey: queryKeys.customerTrustEvents(tenantId, customerId ?? ''),
    queryFn: ({ signal }) => management.getCustomerTrustEvents(customerId!, signal),
    enabled: !!customerId,
  });
}

export function useRescheduleRequests() {
  const { tenantId } = useTenant();
  return useQuery({ queryKey: queryKeys.rescheduleRequests(tenantId), queryFn: ({ signal }) => management.getRescheduleRequests(signal), refetchInterval: 30_000 });
}

function useRescheduleDecision(action: 'approve' | 'reject') {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  return useMutation({
    mutationFn: action === 'approve'
      ? (input: { id: string; reason?: string }) => management.approveRescheduleRequest(input.id)
      : management.rejectRescheduleRequest,
    onSuccess: (result: StaffRescheduleRequest) => {
      client.setQueryData<StaffRescheduleRequest[]>(queryKeys.rescheduleRequests(tenantId), rows => rows?.filter(row => row.id !== result.id));
      void client.invalidateQueries({ queryKey: ['tenant', tenantId, 'appointments'] });
    },
  });
}
export function useApproveRescheduleRequest() { return useRescheduleDecision('approve'); }
export function useRejectRescheduleRequest() { return useRescheduleDecision('reject'); }

export function useBookingSettings() {
  const { tenantId } = useTenant();
  return useQuery({ queryKey: queryKeys.bookingSettings(tenantId), queryFn: ({ signal }) => management.getBookingSettings(signal) });
}
export function useUpdateBookingSettings() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  return useMutation({ mutationFn: management.updateBookingSettings,
    onSuccess: data => client.setQueryData(queryKeys.bookingSettings(tenantId), data) });
}

export function useShopDesign() {
  const { tenantId } = useTenant();
  return useQuery({ queryKey: queryKeys.shopDesign(tenantId), queryFn: ({ signal }) => management.getShopDesign(signal) });
}

export function useSaveShopDesign() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  return useMutation({ mutationFn: management.saveShopDesign,
    onSuccess: data => client.setQueryData(queryKeys.shopDesign(tenantId), data) });
}

export function usePublishShopDesign() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  return useMutation({ mutationFn: management.publishShopDesign,
    onSuccess: data => client.setQueryData(queryKeys.shopDesign(tenantId), data) });
}

export function useRollbackShopDesign() {
  const { tenantId } = useTenant();
  const client = useQueryClient();
  return useMutation({ mutationFn: ({ targetVersion, revision }: { targetVersion: number; revision: number }) => management.rollbackShopDesign(targetVersion, revision),
    onSuccess: data => client.setQueryData(queryKeys.shopDesign(tenantId), data) });
}
