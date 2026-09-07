import { api } from './client';
import type { CurrentUser, InviteInput, InviteResult, Service, ServiceInput, Staff, WorkingHours } from './types';

export async function getMe(signal?: AbortSignal) {
  return (await api.get<CurrentUser>('/api/me', { signal })).data;
}
export async function getServices(signal?: AbortSignal) {
  return (await api.get<Service[]>('/api/services', { signal })).data;
}
export async function createService(input: ServiceInput) {
  return (await api.post<Service>('/api/services', input)).data;
}
export async function updateService({ id, ...input }: ServiceInput & { id: string }) {
  return (await api.patch<Service>(`/api/services/${id}`, input)).data;
}
export async function deleteService(id: string) {
  await api.delete(`/api/services/${id}`);
}
export async function getStaff(signal?: AbortSignal) {
  return (await api.get<Staff[]>('/api/staff', { signal })).data;
}
export async function inviteStaff(input: InviteInput) {
  return (await api.post<InviteResult>('/api/staff/invite', input)).data;
}
export async function getWorkingHours(personelId: string, signal?: AbortSignal) {
  return (await api.get<WorkingHours[]>('/api/working-hours', { params: { personelId }, signal })).data;
}

export async function saveWorkingHours(input: import('./types').WeeklyHoursInput) {
  return (await api.put<WorkingHours[]>('/api/working-hours', input)).data;
}
