import axios from 'axios';
import { customerApi } from './customer-client';
import type { BookingRequest, CustomerBooking, CustomerBookings, InAppNotification } from './booking-contract';
import type { PublicSceneManifest } from './shop-scene';

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';
const publicApi = axios.create({ baseURL: API_URL, timeout: 15_000 });

export type PublicService = {
  id: string;
  name: string;
  durationMin: number;
  bufferMin: number;
  price: number;
};

export type PublicStaff = { id: string; displayName: string; chairKey: string };

export type PublicShop = {
  id: string;
  slug: string;
  name: string;
  timezone: string;
  services: PublicService[];
  staff: PublicStaff[];
};

export type Availability = {
  timezone: string;
  date: string;
  slots: { startsAt: string; endsAt: string }[];
};

export async function getPublicShop(slug: string) {
  return (await publicApi.get<PublicShop>(`/api/public/v1/shops/${encodeURIComponent(slug)}`)).data;
}

export type ShopFeatures = { threeD: boolean; pushNotifications: boolean; pwaInstall: boolean };

/** Kontrollü yayın bayrakları. Uç nokta yoksa (eski backend) varsayılanlar kullanılır. */
export async function getShopFeatures(slug: string) {
  return (await publicApi.get<ShopFeatures>(`/api/public/v1/shops/${encodeURIComponent(slug)}/features`)).data;
}

export async function getPublicScene(slug: string) {
  return (await publicApi.get<PublicSceneManifest>(`/api/public/v1/shops/${encodeURIComponent(slug)}/scene`)).data;
}

export async function getAvailability(slug: string, serviceId: string, staffId: string, date: string) {
  return (await publicApi.get<Availability>(`/api/public/v1/shops/${encodeURIComponent(slug)}/availability`, {
    params: { serviceId, staffId, date },
  })).data;
}

export async function createBookingRequest(input: {
  tenantSlug: string;
  serviceId: string;
  staffId: string;
  startsAt: string;
  note?: string;
  source?: 'WEB_LIST' | 'WEB_3D';
  sceneVersion?: number;
  chairKey?: string;
}, idempotencyKey = crypto.randomUUID()) {
  return (await customerApi.post<BookingRequest>('/api/customer/v1/booking-requests', input, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })).data;
}

export async function getBookingRequest(id: string) {
  return (await customerApi.get<BookingRequest>(`/api/customer/v1/booking-requests/${id}`)).data;
}

export async function getCustomerBookings(signal?: AbortSignal) {
  return (await customerApi.get<CustomerBookings>('/api/customer/v1/booking-requests', { signal })).data;
}

export async function cancelBookingRequest(booking: Pick<CustomerBooking, 'id' | 'version' | 'appointmentVersion'>, idempotencyKey = crypto.randomUUID()) {
  return (await customerApi.post<CustomerBooking>(`/api/customer/v1/booking-requests/${booking.id}/cancel`, {
    version: booking.version, appointmentVersion: booking.appointmentVersion,
  }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })).data;
}

export async function getRescheduleAvailability(id: string, date: string) {
  return (await customerApi.get<Availability>(`/api/customer/v1/booking-requests/${id}/reschedule-availability`, { params: { date } })).data;
}

export async function rescheduleBooking(booking: Pick<CustomerBooking, 'id' | 'version' | 'appointmentVersion'>, startsAt: string,
                                        idempotencyKey = crypto.randomUUID()) {
  return (await customerApi.post<CustomerBooking>(`/api/customer/v1/booking-requests/${booking.id}/reschedule`, {
    startsAt, version: booking.version, appointmentVersion: booking.appointmentVersion,
  }, { headers: { 'Idempotency-Key': idempotencyKey } })).data;
}

export async function downloadBookingCalendar(id: string) {
  const response = await customerApi.get<Blob>(`/api/customer/v1/booking-requests/${id}/calendar.ics`, { responseType: 'blob' });
  const url = URL.createObjectURL(response.data);
  const link = document.createElement('a');
  link.href = url; link.download = 'randevu.ics'; link.click();
  URL.revokeObjectURL(url);
}

export async function getCustomerNotifications(signal?: AbortSignal) {
  return (await customerApi.get<InAppNotification[]>('/api/customer/v1/notifications', { signal })).data;
}

export async function markCustomerNotificationRead(id: string) {
  return (await customerApi.post<InAppNotification>(`/api/customer/v1/notifications/${id}/read`)).data;
}

export async function createVisitCode(id: string) {
  return (await customerApi.post<{ appointmentId: string; code: string; expiresAt: string }>(`/api/customer/v1/booking-requests/${id}/visit-code`)).data;
}
