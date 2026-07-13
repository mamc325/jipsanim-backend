# 집사님(Jipsanim) — 진행 상황

> 최종 갱신: 2026-07-13 · 부동산 매물 검증 + 방문 예약/정산 백엔드 (포트폴리오)

Spec Kit 워크플로우(constitution → spec → plan → data-model → contracts → tasks)로 설계하고,
차수별 세로 슬라이스로 구현한다(Constitution 원칙 VI). 각 차수는 Phase 단위 브랜치 → PR → 병합.

## 차수별 상태

| 차수 | 범위 | 상태 |
| --- | --- | --- |
| **1차 MVP** | 회원/권한, 외부 API 배치 수집, 시세 기준(p10~p90/IQR)·관리자 승인, 매물 등록·가격 리스크 검증, QueryDSL 조건 검색 | ✅ 구현 완료 |
| **2차** | 방문 슬롯, Redis Sorted Set 대기열, TTL 예약권(원자적 발급), Mock 결제 확정, 예약 확정 | ✅ 구현 완료 |
| **3차** | 예약 취소/환불(24h 전 전액·슬롯 재개방), 중개사 월별 정산(배치·수수료 20%·carry_over 이월) | ✅ 구현 완료 |
| 4차 | Outbox Pattern, 알림 비동기, 실패 재시도 | ⏳ 예정 |
| 5차 | Elasticsearch + nori 한글 검색 | ⏳ 예정 |
| 6차 | Redis 캐싱/조회수, k6 부하, Sentry/Prometheus/Grafana, Docker/CI | ⏳ 예정 |

**규모**: 프로덕션 Java 167개 파일, 테스트 92개(@Test). 3차 신규 20개 파일 · 테스트 32개.

## 기술 스택

- Spring Boot 3.5.16 / Java 21 / Gradle Kotlin DSL
- Spring MVC · Spring Data JPA · QueryDSL 5.1.0 · Spring Security + JWT(jjwt 0.12.6)
- MySQL 8 · Redis 7(Spring Data Redis) · Testcontainers(MySQL + Redis)
- 외부 API: 행정안전부 주소 API, 국토교통부 실거래가 API (WebClient bounded concurrency)

## 1차 MVP (구현 완료)

- 회원/권한(USER·REALTOR·ADMIN), JWT 인증
- 외부 실거래가 **배치 수집**(WebClient `Flux.flatMap` bounded concurrency)
- 시세 기준: **IQR / p10~p90** 통계 산출, 표본 미달 시 `INSUFFICIENT_DATA` 게이팅, 관리자 승인 워크플로우
- 매물 등록 + **가격 리스크 검증 엔진**(시세 범위 밖 탐지)
- 승인 매물 **QueryDSL 동적 조건 검색**
- 부하 측정: HikariCP 풀 튜닝 **RPS +84%**, 배치 동시성 **x8.1** (`docs/load-test-results.md`)

## 2차 방문 예약 대기열 (구현 완료)

- 방문 슬롯 CRUD, **Redis Sorted Set 대기열** + TTL 예약권
- **Lua Script 원자적** enqueue / tryIssue / releaseToken — 슬롯당 활성 토큰 1개 보장
- Mock 결제 확정 → 예약 CONFIRMED, 슬롯 RESERVED (락 순서 Payment→Reservation→VisitSlot)
- 앱 관리 유니크 컬럼(`active_reservation_key`)으로 슬롯당 확정 1건 보장(MySQL 부분 유니크 한계 우회)
- TransactionSynchronization `afterCommit` 으로 Redis 정리, sweep 스케줄러로 만료 예약권 회수
- **k6 동시 500명** 대기열 진입 → 정확히 1건 확정·중복 0, p95 364ms

## 3차 예약 취소/환불 + 월별 정산 (구현 완료)

설계: `specs/003-refund-settlement/` · 계약: `contracts/api-contract.md`

### Phase 1 — 엔티티/상태 확장
- `Refund`(payment_id UNIQUE → 중복 환불 방지), `Settlement`(UNIQUE realtor_id+settlement_month)
- Payment `REFUNDED` 상태, `refund()`(PAID→REFUNDED·paidAt 유지), VisitSlot `reopen()`(RESERVED→OPEN)

### Phase 2 — 예약 취소/환불
- `POST /api/reservations/{id}/cancellation` [USER]
- **①잠금(Payment→Reservation→VisitSlot 전부) → ②검증(멱등/소유자/CONFIRMED/24h) → ③변경** 순서 고정
- 주입 `Clock` 으로 24h 판정, 취소 → Refund 생성·Payment REFUNDED·예약 CANCELLED·슬롯 재개방

### Phase 3 — 정산 계산 + 배치 (핵심 로직)
- `SettlementCalculator`(순수함수): **이월 먼저 차감 후 floor 수수료**
  `gross=결제-환불-carry_in`, `fee=gross>0?floor(gross*0.2):0`, `payout=max(0,gross-fee)`, `carry_out=max(0,-gross)`
- 대상 realtor **합집합** = 당월 결제 ∪ 당월 환불 ∪ 전월 carry_over_out>0 (이월 연속성)
- 재계산 정책: 같은 realtor 이후 월 존재 시 **전체 요청 409**, PENDING 갱신 / CONFIRMED·PAID skip
- 스케줄러(매월 1일 04:00) + `POST /api/admin/settlement-batch-jobs`(**동기 200**)

### Phase 4 — 정산 조회/확정/지급
- `GET /api/me/settlements` [REALTOR] (userId→Realtor 매핑), `GET /api/admin/settlements` [ADMIN] (필터+페이지)
- `POST .../confirmation`(PENDING→CONFIRMED, 멱등 200), `POST .../payout`(CONFIRMED→PAID, 멱등 200, PENDING 409)

### Phase 5 — 마감
- **E2E**: 예약확정→취소→환불→배치집계→확정→지급→중개사 조회 전 과정 통과
- docs(api-design·ROADMAP·erd)·인수기준 갱신

**정합성 핵심**: 결제 paidAt·환불 refundedAt 기준 월 분리(월 경계 정확), UNIQUE 제약으로 중복 환불·중복 정산 0,
음수 정산 carry_over 이월 연쇄, 상태전이 멱등.

## 다음 단계 (4차)

Outbox Pattern 기반 알림 비동기 처리 — 정산 확정/취소 등 이벤트를 Outbox 에 적재 후 재시도 가능한 발행.
