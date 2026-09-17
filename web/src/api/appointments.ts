import { api } from './client';
export type AppointmentStatus = 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
export type Appointment = {
  id: string; staffId: string; serviceId: string; startsAt: string; endsAt: string; busyUntil: string;
  status: AppointmentStatus; customerName: string | null; customerPhone: string | null; notes: string | null; version: number;
  customerEmail?: string | null; emailNotifications?: boolean;
  customerId?: string | null; visitVerifiedAt?: string | null;
};
export type CreateAppointment = { staffId: string; serviceId: string; startsAt: string; customerName: string; customerPhone: string; notes: string; customerEmail?: string; emailNotifications?: boolean };
export type Availability = { timezone: string; date: string; slots: { startsAt: string; endsAt: string }[] };
export type CalendarClosure = { id: string; staffId: string | null; staffName: string | null; type: 'BREAK' | 'CLOSED_DAY'; startsAt: string; endsAt: string; reason: string | null; version: number };
export type ClosureRequest = { staffId: string | null; type: CalendarClosure['type']; startsAt: string; endsAt: string; reason: string };
export type ClosurePreview = { scopeName: string; appointments: { id: string; staffId: string; staffName: string; customerName: string | null; startsAt: string; endsAt: string }[] };
export const listAppointments = async (from: string, to: string, staffId: string, signal?: AbortSignal) =>
  (await api.get<Appointment[]>('/api/appointments', { params: { from, to, staffId: staffId || undefined }, signal })).data;
export const availability = async (staffId: string, serviceId: string, date: string, signal?: AbortSignal, appointmentId?: string) =>
  (await api.get<Availability>('/api/availability', { params: { staffId, serviceId, date, appointmentId }, signal })).data;
export const createAppointment = async (input: CreateAppointment, key: string) =>
  (await api.post<Appointment>('/api/appointments', input, { headers: { 'Idempotency-Key': key } })).data;
export const patchAppointment = async (id: string, input: { version: number; status?: AppointmentStatus; startsAt?: string; notes?: string }) =>
  (await api.patch<Appointment>('/api/appointments/' + id, input)).data;
export const verifyVisit = async (id: string, code: string) =>
  (await api.post<{ appointmentId: string; verified: boolean; verifiedAt: string }>(`/api/appointments/${id}/visit-verification`, { code })).data;
export const listClosures = async (from: string, to: string, signal?: AbortSignal) =>
  (await api.get<CalendarClosure[]>('/api/calendar-closures', { params: { from, to }, signal })).data;
export const previewClosure = async (input: ClosureRequest) =>
  (await api.post<ClosurePreview>('/api/calendar-closures/preview', input)).data;
export const createClosure = async (input: ClosureRequest) =>
  (await api.post<CalendarClosure>('/api/calendar-closures', input)).data;
export const deleteClosure = async (item: Pick<CalendarClosure, 'id' | 'version'>) =>
  api.delete(`/api/calendar-closures/${item.id}`, { params: { version: item.version } });
