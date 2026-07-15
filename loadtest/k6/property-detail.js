import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './lib/common.js';

// 6차 시나리오 ①: 매물 상세 조회 부하. 익명(cache-first) 요청으로 상세 캐시 hit-ratio·writeback 배치 효과 측정.
// 캐시 효과는 /actuator/prometheus 의 cache_requests_total{cache="detail"} (hit/miss), DB write 감소는
// view_flush_total / view_flush_delta_total 로 확인(부하 종료 후 스크레이프).
export const options = {
  scenarios: {
    detail: {
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
    http_req_duration: ['p(95)<500'],
  },
};

// 시드된 ACTIVE 매물 id 수집(조건검색 목록 활용). 데이터 선시드 필요.
export function setup() {
  const res = http.get(`${BASE_URL}/api/properties?size=100`);
  const content = res.json('data.content') || [];
  const ids = content.map((p) => p.propertyId);
  if (ids.length === 0) {
    throw new Error('시드된 ACTIVE 매물이 없습니다. 먼저 매물을 등록/승인하세요.');
  }
  return { ids };
}

export default function (data) {
  const id = data.ids[Math.floor(Math.random() * data.ids.length)];
  const res = http.get(`${BASE_URL}/api/properties/${id}`);
  check(res, {
    'status 200': (r) => r.status === 200,
    'viewCount 존재': (r) => r.json('data.viewCount') !== undefined,
  });
}
