# 부하테스트 결과

**측정 환경**: 로컬 단일 노드(Apple Silicon), MySQL 8·Redis 7(Docker). → 절대 수치가 아니라 **개선 전후 비교/정합성 검증** 목적.
- 1차: `./gradlew loadTest` (Java 21 가상스레드/HttpClient, `@Tag("load")`)
- 2차: k6(Docker) — `loadtest/k6/reservation-queue.js`

측정 대상:
1. 매물 조건 검색 처리량/지연 (100k ACTIVE 매물 시드) — 1차
2. 동시 승인 정합성 (active_key/후보 상태 경쟁) — 1차
3. 배치 병렬 수집 speedup (WebClient bounded concurrency) — 1차
4. **대기열 동시 500명 진입 → 슬롯당 1명 확정·중복 0** — 2차(k6)

---

## 1. 검색 API — HikariCP 커넥션 풀 튜닝 (개선 전후)

`GET /api/properties?sigunguCode=&size=20`, 100k ACTIVE 매물, 요청 4,000건.

| 동시성 | RPS (pool=10 → 30) | p95 (10 → 30) | p99 (10 → 30) |
| --- | --- | --- | --- |
| 16 | 87 → **160** (+84%) | 293 → **131 ms** (−55%) | 359 → 181 ms |
| 32 | 101 → **151** (+50%) | 556 → **348 ms** (−37%) | 903 → 397 ms |
| 64 | 111 → **138** (+24%) | 959 → **724 ms** (−24%) | 1218 → 875 ms |

- **원인**: 기본 풀 크기 10에서 동시성 16 이상이면 커넥션 대기가 지연·처리량을 지배.
- **조치**: `spring.datasource.hikari.maximum-pool-size: 30` (`application.yml`).
- **결과**: 저동시성 처리량 최대 +84%, p95 최대 −55%.

## 2. 깊은 페이지네이션 & 인덱스

| 항목 | 측정 | 해석 |
| --- | --- | --- |
| page=0 vs page=2000 | 331 ms vs 330 ms (**x1.0**) | offset 자체는 병목 아님 — **count(\*) 쿼리가 지배**(100k 전체 count ≈ 330ms) |
| 인덱스 O vs X (필터 검색) | 45 ms vs 72 ms (**x1.6**) | `idx_property_search(status, sigungu_code, deal_type, property_type)` 효과 |

- **핵심 발견**: `Page` 응답의 별도 `count(*)`가 대용량에서 지배적 비용. offset 최적화(keyset)보다 **count 회피(Slice/무한스크롤)나 필터 선택도 향상**이 효과적.
- **후속 개선 후보**: 무한스크롤용 `Slice`(count 미수행) 엔드포인트, 커버링 인덱스.

## 3. 동시 승인 정합성 — TOCTOU 경쟁 발견·수정

같은 후보를 **50 스레드가 동시에 승인**.

| 단계 | 성공(200) | 멱등(409) | 기타(500) | 최종 ACTIVE |
| --- | --- | --- | --- | --- |
| 초기 | 1 | 20 | **29** | 1 |
| 500→409 매핑 후 | **21** | 0 | 29 | 1 |
| **후보 비관적 락 후** | **1** | **49** | **0** | **1** |

- **발견**: 후보 상태(`PENDING`) 검증에 락/버전이 없어 여러 트랜잭션이 동시에 통과 → 후보가 여러 번 "승인"됨(21건). `active_key` 유니크 덕에 **ACTIVE는 1건**으로 유지됐으나 API가 500 반환.
- **조치 A**: `DataIntegrityViolationException → 409` 매핑(경쟁에서 밀린 요청).
- **조치 B**: 후보 승인/반려 시 `findByIdForUpdate`(`PESSIMISTIC_WRITE`)로 후보 행 잠금 → 승인 직렬화.
- **결과**: 동시 50요청에서 **승인 1건 · 멱등 49건 · 500 0건 · ACTIVE 1건(중복 0)**.

> 부하테스트가 없었다면 놓쳤을 실 동시성 버그. (Constitution II 검증)

## 4. 배치 병렬 수집 speedup — WebClient bounded concurrency

16개 지역, MockWebServer로 응답 지연 150ms 주입.

| concurrency | wall-clock |
| --- | --- |
| 1 (순차) | 2,747 ms |
| 8 (병렬) | **339 ms** |
| **speedup** | **x8.1** |

- `Flux.flatMap(fetch, concurrency=8)`가 순차 대비 **x8.1** 단축 → "비동기 병렬 수집" 실측 근거.

## 5. 대기열 동시 500명 진입 (2차, k6)

`loadtest/k6/reservation-queue.js` — 500 VU 가 **하나의 방문 슬롯에 동시에 대기열 진입**, 예약권 보유자만 예약→결제 확정.

| 지표 | 결과 |
| --- | --- |
| 대기열 진입 응답 | **500/500 = 201** (checks 100%, 진입 실패 0) |
| 예약 확정(reservation_confirmed) | **정확히 1** (`count>0 && count<2` 통과) → 슬롯당 1명, **중복 확정 0** |
| 진입 p95 지연 | **364 ms** (500 동시) |
| 처리 시간 | 500 진입 ~0.6s |

- **원자적 Lua 발급 + `active_reservation_key` UNIQUE + 확정 시 비관적 락**이 결합돼, 500명 동시 진입에도 **예약권 1개·확정 1건**만 성립함을 실측.
- sweep 스케줄러 활성 상태(프로덕션과 동일)에서 측정.

---

## 이력서용 측정 문장 (실측 기반)
- HikariCP 커넥션 풀 튜닝으로 검색 API 처리량 **최대 +84%(87→160 RPS)**, p95 지연 **−55%(293→131ms)** 개선
- 부하테스트로 **동시 승인 TOCTOU 경쟁을 발견**, 후보 행 비관적 락 적용으로 동시 50요청에서 **승인 1건·ACTIVE 1건 보장(중복 0)**
- WebClient bounded concurrency로 16지역 수집을 **순차 대비 x8.1 단축(2747→339ms)**
- 깊은 페이지네이션 분석으로 **count(\*) 쿼리 병목을 규명**, 인덱스 적용 필터 검색 x1.6 확인
- Redis Sorted Set 대기열 + TTL 예약권으로 **동시 500명 진입 시 순번 정합성·중복 예약 0·확정 1건** 검증(k6, p95 364ms)

## 재현
```bash
docker compose up -d
./gradlew loadTest                      # 1차 벤치마크
# 2차 대기열(k6): 앱 기동 후
docker run --rm --add-host=host.docker.internal:host-gateway \
  -e BASE_URL=http://host.docker.internal:8080 -e USERS=500 \
  -v "$PWD/loadtest/k6:/scripts" grafana/k6 run /scripts/reservation-queue.js
```
