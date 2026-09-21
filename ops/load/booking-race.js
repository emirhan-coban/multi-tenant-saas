import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const createdAppointments = new Counter('same_slot_created');
const slotConflicts = new Counter('same_slot_conflict');

export const options = {
  scenarios: {
    availability: { executor: 'constant-vus', vus: 20, duration: '45s', exec: 'availability' },
    same_slot_race: { executor: 'per-vu-iterations', vus: 10, iterations: 1, startTime: '5s', exec: 'race' },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<1000'],
    same_slot_created: ['count==1'],
    same_slot_conflict: ['count==9'],
  },
};

const base = __ENV.BASE_URL;
const slug = __ENV.TENANT_SLUG;
const service = __ENV.SERVICE_ID;
const staff = __ENV.STAFF_ID;
const date = __ENV.DATE;
const slot = __ENV.SLOT;
const tokens = (__ENV.CUSTOMER_TOKENS || '').split(',').filter(Boolean);

export function setup() {
  if (!base || !slug || !service || !staff || !date || !slot || tokens.length < 10) {
    throw new Error('BASE_URL, TENANT_SLUG, SERVICE_ID, STAFF_ID, DATE, SLOT ve en az 10 CUSTOMER_TOKENS gerekli');
  }
}

export function availability() {
  const response = http.get(`${base}/api/public/v1/shops/${slug}/availability?serviceId=${service}&staffId=${staff}&date=${date}`);
  check(response, { 'availability 200': r => r.status === 200 });
  sleep(0.2);
}

export function race() {
  const token = tokens[(__VU - 1) % tokens.length];
  const response = http.post(`${base}/api/customer/v1/booking-requests`, JSON.stringify({ tenantSlug: slug, serviceId: service, staffId: staff, startsAt: slot, source: 'WEB_LIST' }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, 'Idempotency-Key': `load-race-${__VU}` },
  });
  if (response.status === 201) createdAppointments.add(1);
  if (response.status === 409) slotConflicts.add(1);
  check(response, { 'accepted or conflict': r => [201, 409].includes(r.status) });
}

export function handleSummary(data) {
  const created = data.metrics.http_reqs?.values?.count || 0;
  return { stdout: `İstekler: ${created}\nHTTP p95: ${data.metrics.http_req_duration?.values?.['p(95)']} ms\nBaşarısız oran: ${data.metrics.http_req_failed?.values?.rate}\n` };
}
