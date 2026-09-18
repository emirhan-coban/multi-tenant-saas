import { api } from './client';
import type { BookingApprovalRequest, BookingCustomer, BookingLink, BookingSettings, CurrentUser, CustomerTrust, CustomerTrustEvent, InviteInput, InviteResult, Service, ServiceInput, Staff, StaffRescheduleRequest, WorkingHours } from './types';
import type { BookingRequestStatus, CustomerTrustState } from './booking-contract';
import type { ShopDesignState } from './shop-scene';

export async function getMe(signal?: AbortSignal) {
  return (await api.get<CurrentUser>('/api/me', { signal })).data;
}
export async function getBookingLink(signal?: AbortSignal) {
  return (await api.get<BookingLink>('/api/booking-link', { signal })).data;
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
export async function getBookingRequests(status: BookingRequestStatus = 'PENDING_APPROVAL', signal?: AbortSignal) {
  return (await api.get<BookingApprovalRequest[]>('/api/booking-requests', { params: { status }, signal })).data;
}
export async function approveBookingRequest(id: string) {
  return (await api.post<BookingApprovalRequest>(`/api/booking-requests/${id}/approve`, {}, {
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  })).data;
}
export async function rejectBookingRequest({ id, reason }: { id: string; reason?: string }) {
  return (await api.post<BookingApprovalRequest>(`/api/booking-requests/${id}/reject`, { reason: reason || null }, {
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  })).data;
}
export async function updateCustomerTrust({ customerId, trustState, expectedVersion, reason }: { customerId: string; trustState: CustomerTrustState; expectedVersion: number; reason: string }) {
  return (await api.patch<CustomerTrust>(`/api/booking-customers/${customerId}/trust`, { trustState, expectedVersion, reason })).data;
}
export async function getBookingCustomers(signal?: AbortSignal) {
  return (await api.get<BookingCustomer[]>('/api/booking-customers', { signal })).data;
}
export async function getCustomerTrustEvents(customerId: string, signal?: AbortSignal) {
  return (await api.get<CustomerTrustEvent[]>(`/api/booking-customers/${customerId}/trust-events`, { signal })).data;
}
export async function getRescheduleRequests(signal?: AbortSignal) {
  return (await api.get<StaffRescheduleRequest[]>('/api/reschedule-requests', { params: { status: 'PENDING_APPROVAL' }, signal })).data;
}
export async function approveRescheduleRequest(id: string) {
  return (await api.post<StaffRescheduleRequest>(`/api/reschedule-requests/${id}/approve`, {}, { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data;
}
export async function rejectRescheduleRequest({ id, reason }: { id: string; reason?: string }) {
  return (await api.post<StaffRescheduleRequest>(`/api/reschedule-requests/${id}/reject`, { reason: reason || null }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })).data;
}
export async function getBookingSettings(signal?: AbortSignal) {
  return (await api.get<BookingSettings>('/api/booking-settings', { signal })).data;
}
export async function updateBookingSettings({ updatedAt: _updatedAt, ...input }: BookingSettings) {
  return (await api.put<BookingSettings>('/api/booking-settings', input)).data;
}
export async function getShopDesign(signal?: AbortSignal) {
  return (await api.get<ShopDesignState>('/api/shop-design', { signal })).data;
}
export async function saveShopDesign(input: ShopDesignState['draft']) {
  return (await api.put<ShopDesignState>('/api/shop-design/draft', {
    templateKey: input.templateKey, accentColor: input.accentColor,
    revision: input.revision, chairs: input.chairs.map(({ staffName: _staffName, ...chair }) => chair),
  })).data;
}
export async function publishShopDesign(revision: number) {
  return (await api.post<ShopDesignState>('/api/shop-design/publish', { revision })).data;
}
export async function rollbackShopDesign(targetVersion: number, revision: number) {
  return (await api.post<ShopDesignState>('/api/shop-design/rollback', { targetVersion, revision })).data;
}

// Faz 9 — özellik bayrakları (yalnızca işletme sahibi).
export interface FeatureFlagState {
  key: string;
  enabled: boolean;
  globalEnabled: boolean;
  tenantOverride: boolean | null;
}
export async function getFeatureFlags(signal?: AbortSignal) {
  return (await api.get<FeatureFlagState[]>('/api/feature-flags', { signal })).data;
}
export async function updateFeatureFlag({ key, enabled, reason }: { key: string; enabled: boolean | null; reason: string }) {
  return (await api.put<FeatureFlagState[]>(`/api/feature-flags/${encodeURIComponent(key)}`, { enabled, reason })).data;
}
