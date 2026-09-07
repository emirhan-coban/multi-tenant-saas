import { CanceledError } from 'axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import * as management from '../api/management';
import type { InviteInput, InviteResult, Service } from '../api/types';
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
