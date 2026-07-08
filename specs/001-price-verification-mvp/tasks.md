# Tasks: 실거래가 기반 시세 검증 MVP

- Branch: `001-price-verification-mvp`
- 규칙: `[P]` = 서로 다른 파일이라 병렬 가능. TDD — 각 도메인 로직은 테스트 태스크가 구현보다 앞선다.
- 순서: Setup → 공통/인증 → 주소·매물 → 배치·외부 → 시세기준 → 검증·승인 → 검색 → 통합·마감.

## Phase 0. Setup
- [ ] T001 Gradle 프로젝트 초기화(Java 21, Spring Boot 3.x): web, security, data-jpa, validation, webflux(WebClient), querydsl, mysql, lombok
- [ ] T002 [P] `application.yml` 프로퍼티: DB, JWT secret/exp, `MIN_SAMPLE_COUNT=30`, `COLLECT_MONTHS=3`, `WEBCLIENT_CONCURRENCY=8`, 외부 API base/키/timeout
- [ ] T003 [P] Testcontainers(MySQL) + MockWebServer 테스트 베이스 클래스
- [ ] T004 [P] 공통 응답 래퍼 `ApiResponse`, 전역 예외 핸들러, 에러코드 enum (contract 기준)
- [ ] T005 [P] QueryDSL 설정(Q타입 생성) + JPA Auditing(created/updated)

## Phase 1. 인증 / 권한
- [ ] T010 엔티티 `User`, `Realtor` + 리포지토리
- [ ] T011 Spring Security + JWT 필터/프로바이더, 역할 기반 `authorizeHttpRequests`
- [ ] T012 [P] 테스트: 회원가입/로그인/권한 접근(USER가 admin API 403)
- [ ] T013 Auth 서비스/컨트롤러: `POST /auth/signup`, `POST /auth/login`, `GET /me` (FR-001~003)

## Phase 2. 외부 주소 API + 매물 CRUD
- [ ] T020 `external/log`: `ExternalApiCallLog` 엔티티/리포지토리 + 호출 로깅 wrapper (FR-012, D5)
- [ ] T021 [P] 테스트: `AddressClient` (MockWebServer 응답 파싱, timeout→502)
- [ ] T022 `external/address` `AddressClient`(WebClient) + `GET /addresses?keyword=` (FR-010~011)
- [ ] T023 엔티티 `Property`, `PropertyImage` + 리포지토리 (data-model)
- [ ] T024 [P] 테스트: 매물 CRUD 소유자 검증, 상태 제약(DRAFT/PENDING만 수정)
- [ ] T025 매물 컨트롤러/서비스: `POST/PATCH/DELETE /properties`, `GET /properties/{id}` (FR-020~021, 023)

## Phase 3. 실거래가 배치 + 외부 수집
- [ ] T030 엔티티 `PriceStandardBatchJob` + 리포지토리 (BatchStatus)
- [ ] T031 [P] 테스트: `RealEstateClient` XML 파싱(정상/결측/오류), 단일 지역 호출
- [ ] T032 `external/molit` `RealEstateClient`(WebClient, XML→DTO) (FR-030)
- [ ] T033 [P] 테스트: 배치 bounded concurrency 수집 — 일부 지역 실패 시 PARTIAL_FAILED, CallLog 기록 검증 (FR-031~032)
- [ ] T034 `PriceStandardBatchService`: `Flux.flatMap(fetch, concurrency=N)` 수집 → 결과 집계 → BatchJob/CallLog 저장 (plan D1)
- [ ] T035 스케줄러(`@Scheduled` 매월1일 03:00) + 수동 트리거 `POST /admin/price-standard-batch-jobs`(잡 생성), `GET /admin/price-standard-batch-jobs`, `GET /admin/external-api-call-logs` (FR-030, 036)

## Phase 4. 시세 기준 계산 + 후보 승인
- [ ] T040 [P] 테스트: `RangeCalculator` — IQR/백분위, 이상치 제거, 경계값, 소표본→INSUFFICIENT_DATA (Constitution III)
- [ ] T041 `pricestandard/stats` `RangeCalculator`(순수함수, PERCENTILE|IQR) (FR-033~034, plan D2)
- [ ] T042 엔티티 `PriceStandardCandidate`, `PriceStandard`(+`active_key` 생성컬럼 UNIQUE), `PriceStandardHistory` + 리포지토리
- [ ] T043 후보 생성 서비스: 수집 표본 그룹핑 → RangeCalculator → Candidate(PENDING) 저장 (FR-033~034)
- [ ] T044 [P] 테스트: 후보 승인 ACTIVE 교체 — 기존 EXPIRED, 신규 ACTIVE 유일성(active_key), History 생성, 멱등(ALREADY_REVIEWED) (plan D3)
- [ ] T045 후보 승인/반려 서비스+컨트롤러: `POST …/candidates/{id}/approval|rejection`, `GET …/candidates`, `GET …/price-standards` (FR-035~036)

## Phase 5. 매물 자동 검증 + 관리자 승인
- [ ] T050 엔티티 `PropertyVerification`, `PropertyVerificationReason` + 리포지토리
- [ ] T051 [P] 테스트: 각 `VerificationRule`(필수/이미지/설명/주소-지역/중복) 단위
- [ ] T052 [P] 테스트: 가격 규칙 — ACTIVE 기준 이탈→PRICE_OUT_OF_RANGE, 기준없음/INSUFFICIENT→HIGH 금지/REVIEW_REQUIRED (FR-041~042)
- [ ] T053 검증 엔진: `VerificationRule` 목록 + `RiskScorer`(riskLevel 산정) (plan D4, FR-040~045)
- [ ] T054 `POST /properties/{id}/submission` 연결: 검증 실행→Verification 저장→Property PENDING (FR-022)
- [ ] T055 [P] 테스트: 관리자 승인/반려 상태전이 + 멱등
- [ ] T056 관리자 검증 컨트롤러: `GET /admin/property-verifications`(status/riskLevel 필터), `POST …/{id}/approval`, `POST …/{id}/rejection`; 승인 시 도메인 이벤트 발행(4차 대비, plan D6) (FR-050)

## Phase 6. 검색
- [ ] T060 [P] 테스트: QueryDSL 조건 검색 — ACTIVE만, 각 필터/정렬/페이지 (FR-051)
- [ ] T061 `PropertySearchRepository`(QueryDSL) + `GET /properties` 조건 검색 컨트롤러

## Phase 7. 통합 & 마감
- [ ] T070 통합 시나리오 테스트(Testcontainers): 주소검색→매물 submit→검증저장→관리자승인→검색노출
- [ ] T071 통합: 배치 수집→후보생성→후보승인(ACTIVE 교체)→해당 지역 매물 가격검증 반영
- [ ] T072 [P] 인수기준(spec §7) 체크리스트 검증, README 실행법
- [ ] T073 [P] seed 데이터/샘플 regionCode 목록, 외부 API 키 발급 가이드 문서화

## 병렬 실행 힌트
- T002/T003/T004/T005 동시 가능(Setup).
- 각 Phase의 `[P]` 테스트 태스크는 해당 구현 태스크보다 먼저 착수.
- Phase 3(배치)와 Phase 5(검증 규칙 단위)는 데이터 의존이 적어 부분 병렬 가능하나, 가격 규칙(T052/T053)은 Phase 4(ACTIVE 기준) 이후 통합.

## 의존성 요약
```
Setup(0) → 인증(1) → 매물CRUD(2) ─┐
                                   ├→ 검증엔진(5) → 검색(6) → 통합(7)
배치·외부(3) → 시세기준(4) ───────┘
```
