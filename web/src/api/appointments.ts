import { api } from './client';
export type AppointmentStatus = 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
export type Appointment = {
  id: string; staffId: string; serviceId: string; startsAt: string; endsAt: string; busyUntil: string;
  status: AppointmentStatus; customerName: string | null; customerPhone: string | null; notes: string | null; version: number;
};
export type CreateAppointment = { staffId: string; serviceId: string; startsAt: string; customerName: string; customerPhone: string; notes: string };
export type Availability = { timezone: string; date: string; slots: { startsAt: string; endsAt: string }[] };
export const listAppointments = async (from: string, to: string, staffId: string, signal?: AbortSignal) =>
  (await api.get<Appointment[]>('/api/appointments', { params: { from, to, staffId: staffId || undefined }, signal })).data;
export const availability = async (staffId: string, serviceId: string, date: string, signal?: AbortSignal, appointmentId?: string) =>
  (await api.get<Availability>('/api/availability', { params: { staffId, serviceId, date, appointmentId }, signal })).data;
export const createAppointment = async (input: CreateAppointment, key: string) =>
  (await api.post<Appointment>('/api/appointments', input, { headers: { 'Idempotency-Key': key } })).data;
export const patchAppointment = async (id: string, input: { version: number; status?: AppointmentStatus; startsAt?: string; notes?: string }) =>
  (await api.patch<Appointment>('/api/appointments/' + id, input)).data;
