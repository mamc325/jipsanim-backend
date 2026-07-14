# 집사님(Jipsanim) — 진행 상황

> 최종 갱신: 2026-07-14 · 부동산 매물 검증 + 방문 예약/정산 백엔드 (포트폴리오)

Spec Kit 워크플로우(constitution → spec → plan → data-model → contracts → tasks)로 설계하고,
차수별 세로 슬라이스로 구현한다(Constitution 원칙 VI). 각 차수는 Phase 단위 브랜치 → PR → 병합.

## 차수별 상태

| 차수 | 범위 | 상태 |
| --- | --- | --- |
| **1차 MVP** | 회원/권한, 외부 API 배치 수집, 시세 기준(p10~p90/IQR)·관리자 승인, 매물 등록·가격 리스크 검증, QueryDSL 조건 검색 | ✅ 구현 완료 |
| **2차** | 방문 슬롯, Redis Sorted Set 대기열, TTL 예약권(원자적 발급), Mock 결제 확정, 예약 확정 | ✅ 구현 완료 |
| **3차** | 예약 취소/환불(24h 전 전액·슬롯 재개방), 중개사 월별 정산(배치·수수료 20%·carry_over 이월) | ✅ 구현 완료 |
| **4차** | Outbox Pattern(동일 커밋 적재·폴링 Worker·SKIP LOCKED), Mock 알림 비동기, 지수 백오프 재시도·DEAD·수동 재처리, 이중 멱등 | ✅ 구현 완료 |
| **5차** | Elasticsearch + nori 한글 형태소 검색(Outbox 기반 색인 동기화·필드 부스팅·decompound) | ✅ 구현 완료 |
| 6차 | Redis 캐싱/조회수, k6 부하, Sentry/Prometheus/Grafana, Docker/CI | ⏳ 예정 |

**규모**: 프로덕션 Java 200개 파일, 테스트 131개(@Test). 5차 신규(search+Outbox 라우팅) 12개 파일 · 테스트 10개.

## 기술 스택

- Spring Boot 3.5.16 / Java 21 / Gradle Kotlin DSL
- Spring MVC · Spring Data JPA · QueryDSL 5.1.0 · Spring Security + JWT(jjwt 0.12.6)
- MySQL 8 · Redis 7(Spring Data Redis) · **Elasticsearch 8 + nori**(Spring Data Elasticsearch) · Testcontainers(MySQL + Redis + Elasticsearch)
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

## 4차 Outbox 기반 비동기 알림 (구현 완료)

설계: `specs/004-outbox-notification/` · 도메인 트랜잭션과 알림 발행의 원자성 분리(Constitution 원칙 IV)

### Phase 1~5
- **엔티티/상태**: `OutboxEvent`(event_key UNIQUE, processing_started_at, payload JSON) + `RetryPolicy`(백오프 1m·5m·15m·1h·6h, attempts≥6 DEAD), `Notification`(outbox_event_id UNIQUE)
- **Producer**: 도메인 서비스가 같은 트랜잭션에서 `OutboxEventPublisher.append()` — native `INSERT ... ON DUPLICATE KEY UPDATE id=id` 로 producer 멱등(트랜잭션 오염 없이 중복 no-op)
- **Consumer**: `NotificationDispatcher`(7종 렌더링) + `MockNotificationSender`(포트 분리), 소비 멱등(outbox_event_id)
- **Worker**: 폴링 + `FOR UPDATE SKIP LOCKED` 선점 + `processing_started_at` reaper(고착 복구) + `REQUIRES_NEW` 실패 기록 → 지수 백오프/DEAD
- **도메인 7종**: 예약 확정/취소/환불/정산 지급/매물 승인·반려/예약권 발급(best-effort, Redis INCR invitationSeq)
- **API**: `/me/notifications`(조회·읽음), `/admin/outbox-events`(모니터링·DEAD 재처리)

**신뢰성 핵심**: 적재는 도메인 커밋과 원자적(유령 알림 0), 이중 멱등(producer event_key + consumer outbox_event_id)으로 중복 알림 0,
at-least-once + 지수 백오프 재시도 + DEAD 격리·수동 재처리. 알림 실패가 도메인 트랜잭션으로 전파되지 않음.

## 5차 Elasticsearch + nori 한글 검색 (구현 완료)

설계: `specs/005-search-elasticsearch/` · latency 가 아닌 **검색 품질/관련도**로 어필(Constitution VII) · 벤치마크 계획: `docs/search-benchmark.md`

### Phase 1 — ES/nori 인프라 + 인덱스
- `analysis-nori` 플러그인 내장 커스텀 이미지(`docker/elasticsearch-nori/Dockerfile`), docker-compose 서비스
- `PropertyDocument`(`@Document`, `createIndex=false`) + `es/property-index.json`(settings: `nori_tokenizer decompound_mode=mixed` + `korean_pos_filter` + `korean_nori` analyzer / mappings)
- `PropertyIndexBootstrap`(ApplicationRunner) 인덱스 생성, `@ConditionalOnProperty(search.elasticsearch.enabled)` 게이팅 — 미설정 환경/공통 테스트는 ES 없이 부팅
- 시간 필드는 `Instant`(`epoch_millis`)로 통일(LocalDateTime zone 모호성 회피)

### Phase 2 — Outbox Worker 핸들러 라우팅
- `OutboxEventHandler`(supports/handle) 레지스트리 도입 — `OutboxWorker` 가 `eventType` 으로 핸들러 선택
- `NotificationOutboxHandler`(알림 7종) vs `PropertyIndexOutboxHandler`(색인) 분리 라우팅 — 4차 알림 경로 무영향

### Phase 3 — 색인 동기화(Outbox 재사용)
- `PropertyIndexEventRecorder`: 승인/삭제/반려 시 같은 커밋에 `PROPERTY_INDEX`/`PROPERTY_UNINDEX` 적재(4차 이중 멱등 그대로)
- 전이 규칙 `prev==ACTIVE && new!=ACTIVE` 일반형 — 승인(→ACTIVE)만 색인, ACTIVE 이탈만 색인 제거
- `PropertyIndexOutboxHandler`: INDEX 는 매물 재조회 후 ACTIVE 면 upsert·아니면 삭제, UNINDEX 는 삭제(소비 멱등)

### Phase 4 — 검색 서비스 + 엔드포인트
- `GET /api/properties/search`(공개, `@ConditionalOnProperty` 게이팅) — 기존 `GET /api/properties`(QueryDSL) 무영향
- `multi_match` 필드 부스팅(`title^3`·`nearStation^2`·`regionName^2`·`description`), `status=ACTIVE` 강제, `term`/`range` 필터
- `track_total_hits=true`, tie-breaker 정렬(`_score→createdAt→propertyId`), deep pagination·`size` 검증(400)
- ES 장애 시 `SEARCH_UNAVAILABLE`(503) 로 degrade

### Phase 5 — 마감
- E2E(`PropertySearchE2ETest`): 승인→색인→**검색 노출(HTTP)**→삭제→**검색 제외(HTTP)** 전 구간
- 인수 기준 체크(`spec.md §8`) 완료

**검증 핵심**: 색인 동기화를 4차 Outbox 로 재사용해 **DB↔ES 정합성**을 커밋 원자성 + 이중 멱등으로 보장(유령/누락 색인 0). nori 형태소 매칭·필드 부스팅·복합어 decompound(`전력→한국전력공사`) 실측.

> 발견: 설계 예시 `역세권`은 `korean_pos_filter` stoptags(XSN) 로 접미사 "권"이 제거돼 복합어 원형 미보존(→`[역세]`) → 검색어로 부적합. 향후 user_dictionary 등록 또는 XSN 제외로 개선(`spec.md §8` 기록).

## 다음 단계 (6차)

Redis 캐싱/조회수, k6 부하 테스트, Sentry/Prometheus/Grafana 관측성, Docker/CI 파이프라인.
