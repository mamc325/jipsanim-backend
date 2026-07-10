import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './lib/common.js';

// 파이프라인 확인용 스모크: health 엔드포인트 짧은 부하
export const options = {
  vus: 10,
  duration: '10s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/actuator/health`);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
