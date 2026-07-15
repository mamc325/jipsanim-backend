import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, randomFrom } from './lib/common.js';

// 6차 시나리오 ③: 5차 nori 전문검색 baseline. GET /api/properties/search?q= (ES). 조건검색(search.js)과 별개.
// 근거는 latency 가 아니라 검색 품질(원칙 VII) — 여기서는 안정적 지연 baseline 확인용.
export const options = {
  scenarios: {
    esSearch: {
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
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<800'],
  },
};

const QUERIES = ['강남역 오피스텔', '역세권 풀옵션', '신축 원룸', '테헤란로'];

export default function () {
  const q = encodeURIComponent(randomFrom(QUERIES));
  const res = http.get(`${BASE_URL}/api/properties/search?q=${q}&size=20`);
  check(res, { 'status 200 또는 503(ES off)': (r) => r.status === 200 || r.status === 503 });
}
