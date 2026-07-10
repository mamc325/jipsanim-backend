# k6 부하테스트 (2차~)

k6를 **Docker로 실행**한다(로컬 설치 불필요). 컨테이너에서 호스트의 앱을 `host.docker.internal`로 호출.

## 사전 준비
```bash
docker compose up -d                                   # MySQL(+Redis, 2차부터)
./gradlew bootRun --args='--spring.profiles.active=local'   # 앱 기동(8080)
```

## 실행
```bash
# 스모크(파이프라인 확인)
docker run --rm --add-host=host.docker.internal:host-gateway \
  -v "$PWD/loadtest/k6:/scripts" grafana/k6 run /scripts/smoke.js

# 검색 부하 (예시 템플릿)
docker run --rm --add-host=host.docker.internal:host-gateway \
  -v "$PWD/loadtest/k6:/scripts" grafana/k6 run /scripts/search.js

# BASE_URL 오버라이드
docker run --rm --add-host=host.docker.internal:host-gateway \
  -e BASE_URL=http://host.docker.internal:8080 \
  -v "$PWD/loadtest/k6:/scripts" grafana/k6 run /scripts/search.js
```

## 스크립트
| 파일 | 시나리오 |
| --- | --- |
| `smoke.js` | 파이프라인 확인(health) |
| `search.js` | 매물 검색 처리량/지연 (1차 검색 API) |
| `reservation-queue.js` | (2차) 동시 N명 대기열 진입 → 순번/예약권/중복예약 방지 검증 |

## 지표 해석
- `http_req_duration` p95/p99, `http_req_failed` rate, `iterations`(처리량)
- 임계값(`thresholds`) 미달 시 k6가 exit code ≠ 0 → CI 게이트로 사용 가능

> 측정 환경(로컬 단일 노드)은 절대 수치가 아닌 상대 비교/정합성 검증 목적. (docs/load-test-results.md 와 동일 원칙)
