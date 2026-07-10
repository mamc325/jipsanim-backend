import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, authHeaders } from './lib/common.js';

// 동시 N명 대기열 진입 → 슬롯당 1명만 예약권/확정, 순번 정합·중복 0 검증.
// 실행: docker run --rm --add-host=host.docker.internal:host-gateway -v "$PWD/loadtest/k6:/scripts" grafana/k6 run /scripts/reservation-queue.js
const USERS = parseInt(__ENV.USERS || '500');

const confirmed = new Counter('reservation_confirmed');
const grantedButFailed = new Counter('granted_but_reservation_failed');

export const options = {
  setupTimeout: '300s', // 500명 가입(BCrypt) 준비 시간
  scenarios: {
    enter: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '60s' },
  },
  thresholds: {
    'checks': ['rate>0.99'],                 // 진입 응답 정상
    'reservation_confirmed': ['count>0', 'count<2'], // 확정은 정확히 1건 (슬롯당 1명, 중복 0)
    'http_req_duration{name:waiting}': ['p(95)<2000'],
  },
};

function post(url, token) {
  return http.post(url, null, Object.assign({ tags: { name: tagOf(url) } }, authHeaders(token)));
}
function tagOf(url) {
  if (url.endsWith('/waiting')) return 'waiting';
  if (url.endsWith('/reservations')) return 'reservation';
  if (url.endsWith('/confirmation')) return 'confirm';
  return 'other';
}

export function setup() {
  const run = Date.now(); // 실행마다 고유 이메일(중복 가입 방지)
  // 관리자/중개사 준비
  const admin = signupLogin(`q.admin.${run}@test.com`, 'ADMIN', {});
  const realtor = signupLogin(`q.realtor.${run}@test.com`, 'REALTOR', { businessName: '공인', phone: '010' });

  // 매물 등록 → 검증요청 → 관리자 승인(ACTIVE)
  const propertyId = http.post(`${BASE_URL}/api/properties`, JSON.stringify({
    title: '대기열 매물', description: '설명 텍스트 채광 좋은 오피스텔', roadAddress: '서울 강남구 A로 1',
    bjdongCode: '1168010100', regionName: '강남구', propertyType: 'OFFICETEL', dealType: 'MONTHLY_RENT',
    deposit: 10000000, monthlyRent: 700000, area: 33.0, roomCount: 1,
    images: [{ imageUrl: 'https://img/1.jpg', isPrimary: true }],
  }), authHeaders(realtor)).json('data.propertyId');

  http.post(`${BASE_URL}/api/properties/${propertyId}/submission`, null, authHeaders(realtor));
  const list = http.get(`${BASE_URL}/api/admin/property-verifications?size=200`, authHeaders(admin)).json('data.content');
  const verificationId = list.find((v) => v.propertyId === propertyId).verificationId;
  http.post(`${BASE_URL}/api/admin/property-verifications/${verificationId}/approval`, null, authHeaders(admin));

  // 방문 슬롯(미래) 생성
  const start = new Date(Date.now() + 86400000).toISOString().slice(0, 19);
  const end = new Date(Date.now() + 86400000 + 1800000).toISOString().slice(0, 19);
  const slotId = http.post(`${BASE_URL}/api/properties/${propertyId}/visit-slots`,
    JSON.stringify({ startTime: start, endTime: end }), authHeaders(realtor)).json('data.visitSlotId');

  // N명 USER 토큰
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    tokens.push(signupLogin(`q.user${i}.${run}@test.com`, 'USER', {}));
  }
  return { slotId, tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const slotId = data.slotId;

  const enter = post(`${BASE_URL}/api/visit-slots/${slotId}/waiting`, token);
  check(enter, { 'waiting 201': (r) => r.status === 201 });

  if (enter.status === 201 && enter.json('data.tokenGranted') === true) {
    // 예약권 보유자만: 예약 → 확정
    const resv = post(`${BASE_URL}/api/visit-slots/${slotId}/reservations`, token);
    if (resv.status === 201) {
      const paymentId = resv.json('data.paymentId');
      const conf = post(`${BASE_URL}/api/payments/${paymentId}/confirmation`, token);
      if (conf.status === 200) {
        confirmed.add(1);
      } else {
        grantedButFailed.add(1);
      }
    } else {
      grantedButFailed.add(1);
    }
  }
}

function signupLogin(email, role, extra) {
  const body = Object.assign({ email, password: 'password1', nickname: 'u', role }, extra);
  const json = { headers: { 'Content-Type': 'application/json' } };
  http.post(`${BASE_URL}/api/auth/signup`, JSON.stringify(body), json);
  for (let attempt = 0; attempt < 3; attempt++) {
    const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email, password: 'password1' }), json);
    if (res.status === 200 && res.body) {
      return res.json('data.accessToken');
    }
  }
  throw new Error('login failed for ' + email);
}
