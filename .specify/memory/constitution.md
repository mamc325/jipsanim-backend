# 집사님 (Jipsanim) Constitution

프로젝트 전체에 적용되는 최상위 원칙. spec / plan / tasks 는 이 문서와 충돌할 수 없으며,
충돌 시 이 문서가 우선한다. 원칙을 위반해야 한다면 먼저 이 문서를 개정한다.

- Version: 1.0.0
- Ratified: 2026-07-07
- Last Amended: 2026-07-07

---

## Core Principles

### I. 외부 API는 요청 경로에서 격리한다 (External API Isolation)
외부 API(국토교통부 실거래가, 행정안전부 주소)는 **배치 수집 및 주소 검색 시점에만** 호출한다.
- 매물 가격 검증은 **내부 `PriceStandard(ACTIVE)` 기준만** 조회한다. 검증 요청 처리 중 외부 실거래가 API를 실시간 호출하지 않는다.
- 외부 API 장애/timeout 은 핵심 요청(검증·검색)의 응답 시간이나 성공 여부에 영향을 주면 안 된다.
- 모든 외부 호출은 `ExternalApiCallLog` 에 성공/실패·소요시간을 남긴다.
- 근거: 외부 의존성 장애가 서비스 핵심 흐름으로 전파되지 않게 한다.

### II. 상태 기반 멱등성과 원자성 (Idempotency & Atomicity)
동일 요청이 중복 유입돼도 도메인 결과(승인·예약·정산·알림)가 중복 생성되지 않아야 한다.
- 상태 전이는 **현재 상태를 조건으로 검증한 뒤** 수행한다(예: `READY → PAID` 는 `READY` 일 때만).
- 중복 방지가 필요한 지점은 DB `UNIQUE` 제약을 최종 방어선으로 둔다.
- Redis 기반 순번/예약권 발급은 **원자적 연산**(Lua Script 또는 `ZPOPMIN` + `SET NX`)으로만 수행한다. Worker 가 여러 개 떠도 슬롯당 active token 은 최대 1개다.
- 근거: 재시도·동시성·중복 요청 하에서 정합성을 보장한다.

### III. 시세 기준은 통계적으로 신뢰 가능해야 한다 (Statistically Sound Standards)
시세 기준은 단순 min/max 를 사용하지 않는다.
- 지역·매물유형·거래유형별로 **p10~p90 또는 IQR(Q1−1.5·IQR ~ Q3+1.5·IQR) 기반**으로 정상 범위를 산출한다.
- 최소 표본 수(`MIN_SAMPLE_COUNT`) 미달 지역은 가격 이상치로 **HIGH 판정하지 않는다**. `INSUFFICIENT_DATA` 로 표시하고 검증 결과는 `REVIEW_REQUIRED` 로 관리자에게 넘긴다.
- 근거: 표본이 얕거나 이상치가 섞인 기준으로 매물을 위험 판정하면 검증 신뢰도가 무너진다.

### IV. 핵심 트랜잭션과 후속 작업을 분리한다 (Outbox for Side Effects)
알림·검색 색인 등 후속 작업은 핵심 트랜잭션과 같은 커밋 안에서 `OutboxEvent` 로 기록하고, Worker 가 비동기 처리한다.
- 알림/색인 실패가 예약 확정·매물 승인 실패로 이어지면 안 된다.
- 서버 재시작 후에도 미처리(PENDING/RETRY_WAITING) 이벤트는 재처리 가능해야 한다.
- (본 원칙은 Outbox 도입 차수부터 강제된다. 그 전까지 후속 작업은 동일 트랜잭션 내 동기 처리로 두되, 도메인 로직에 강결합시키지 않는다.)

### V. 정합성 우선, 반응형은 경계에서만 (Consistency First)
예약·결제·환불·정산은 Spring MVC + JPA 트랜잭션으로 처리한다.
- WebFlux 전면 도입 금지. `Mono/Flux` 는 외부 API Client 계층 안에서만 사용한다.
- WebClient 흐름을 Controller~Repository 까지 끌고 가지 않는다. 응답은 즉시 DTO 로 변환해 배치/서비스가 JPA 로 처리한다.
- "비동기 병렬 수집"이라는 표현은 **실제 bounded concurrency**(`Flux.flatMap(fn, concurrency=N)`)를 구현했을 때만 사용한다. 순차 `.block()` 반복은 병렬이 아니다.

### VI. 스코프는 차수로 분리하고 각 차수를 완주한다 (Incremental & Vertical Slices)
한 번에 모든 기능을 붙이지 않는다. 차수별로 "완주 가능한 세로 슬라이스"를 정의하고, 이전 차수가 동작·테스트된 뒤 다음으로 넘어간다. 차수 정의는 `ROADMAP.md` 를 따른다.

### VII. 주장은 측정 후에 한다 (Measure Before Claiming)
이력서/문서의 성능·정합성 수치는 **미리 적지 않는다.** 실제 부하 테스트·검증 후 측정값으로 채운다.
- 검색 고도화는 latency 수치가 아니라 **nori analyzer 기반 한글 검색 품질/관련도**를 우선 근거로 삼는다.

### VIII. 상태 전이와 정합성은 테스트로 고정한다 (Test the State Machine)
가격 검증 판정, 상태 전이(매물/후보/예약/결제), 멱등성, 월 경계 케이스는 단위·통합 테스트로 고정한다. 외부 의존성은 Testcontainers(MySQL/Redis) 로 통합 검증한다.

---

## Technology Constraints

- Backend: Java 17+, Spring Boot, Spring MVC, Spring Security(JWT), Spring Data JPA, QueryDSL, Bean Validation
- External API Client: Spring WebClient (경계 계층 한정)
- DB: MySQL (트랜잭션·UNIQUE 제약·월별 집계)
- 배치: Spring Scheduler + WebClient
- 후속 처리: Outbox Pattern (해당 차수부터)
- Cache/Queue: Redis (Sorted Set 대기열, TTL 예약권, 캐시/카운터 — 해당 차수부터)
- Search: Elasticsearch + nori (후순위 차수)
- Test: JUnit5, Mockito, Spring Boot Test, Testcontainers, k6

## Development Workflow

- 모든 기능은 `specs/<번호>-<슬러그>/` 하위에 spec → plan → data-model → contracts → tasks 순으로 문서화한 뒤 구현한다.
- 핵심 상태 전이/정합성 로직은 테스트를 먼저 작성한다.
- PR 은 해당 차수의 tasks 항목과 매핑되어야 하며, 원칙 위반 시 사유를 plan 의 Complexity Tracking 에 남긴다.

## Governance

- 본 constitution 이 다른 모든 문서에 우선한다.
- 개정은 버전을 올리고(MAJOR: 원칙 삭제/재정의, MINOR: 원칙 추가, PATCH: 문구 보정) 개정일을 기록한다.
- plan/tasks 리뷰 시 각 원칙 준수 여부를 확인한다.
