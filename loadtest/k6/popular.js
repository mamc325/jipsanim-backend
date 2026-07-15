import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './lib/common.js';

// 6차 시나리오 ②: 인기 매물 목록 부하. cache-aside 단일 키(popular:list, TTL 60s) → 대부분 cache hit.
// hit-ratio 는 /actuator/prometheus 의 cache_requests_total{cache="popular"} 로 확인.
export const options = {
  scenarios: {
    popular: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50 },
        { duration: '30s', target: 50 },
        { duration: '5s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300'],
  },
};

const LIMITS = [10, 20, 50];

export default function () {
  const limit = LIMITS[Math.floor(Math.random() * LIMITS.length)];
  const res = http.get(`${BASE_URL}/api/properties/popular?limit=${limit}`);
  check(res, {
    'status 200': (r) => r.status === 200,
    'list 반환': (r) => Array.isArray(r.json('data')),
  });
}
