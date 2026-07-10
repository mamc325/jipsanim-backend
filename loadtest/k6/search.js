import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, SIGUNGU, randomFrom } from './lib/common.js';

// 매물 조건 검색 처리량/지연 (1차 검색 API). 동시성 램프업.
export const options = {
  scenarios: {
    search: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 32 },
        { duration: '20s', target: 32 },
        { duration: '5s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  const sg = randomFrom(SIGUNGU);
  const res = http.get(`${BASE_URL}/api/properties?sigunguCode=${sg}&size=20`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
