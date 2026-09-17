import { api } from './client';
import { customerApi } from './customer-client';

/**
 * Faz 9 — hesap güvenliği ve KVKK uçları.
 *
 * "Şifremi unuttum" isteği e-posta kayıtlı olmasa da başarıyla döner: yanıttan hangi
 * adreslerin sistemde olduğu anlaşılamaz. Bu yüzden arayüz de her durumda aynı mesajı gösterir.
 */
const audience = (customer: boolean) => (customer ? customerApi : api);
const prefix = (customer: boolean) => (customer ? '/api/customer/v1/auth' : '/api/auth');

export async function requestPasswordReset(email: string, customer: boolean) {
  await audience(customer).post(`${prefix(customer)}/password/forgot`, { email });
}

export async function resetPassword(token: string, password: string, customer: boolean) {
  await audience(customer).post(`${prefix(customer)}/password/reset`, { token, password });
}

export async function requestEmailVerification(customer: boolean) {
  await audience(customer).post(`${prefix(customer)}/email/verification`);
}

export async function verifyEmail(token: string, customer: boolean) {
  await audience(customer).post(`${prefix(customer)}/email/verify`, { token });
}

export async function emailStatus(customer: boolean, signal?: AbortSignal) {
  return (await audience(customer).get<{ emailVerified: boolean }>(`${prefix(customer)}/email/status`, { signal })).data;
}

export async function kvkkPolicy(signal?: AbortSignal) {
  return (await api.get<{ version: string }>('/api/public/kvkk/policy', { signal })).data;
}

/** Müşterinin kendi verisi; tarayıcıda dosya olarak indirilir. */
export async function exportMyData() {
  const response = await customerApi.get('/api/customer/v1/account/export', { responseType: 'blob' });
  return response.data as Blob;
}

export async function deleteMyAccount(confirmation: string) {
  await customerApi.post('/api/customer/v1/account/delete', { confirmation });
}
