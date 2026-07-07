# Feature Spec: 실거래가 기반 시세 검증 MVP

- Feature Branch: `001-price-verification-mvp`
- Created: 2026-07-07
- Status: Draft
- Constitution: v1.0.0

## 1. 목적 (Why)

중개사가 등록한 매물이 "검증된 매물"로만 사용자에게 노출되도록 한다. 검증의 핵심은 **외부 실거래가로 만든 내부 시세 기준과 비교한 가격 리스크 판정**이다. 외부 API 장애가 검증·검색 요청에 영향을 주지 않도록, 시세 기준은 배치로 미리 갱신해 둔 내부 `PriceStandard(ACTIVE)` 만 사용한다.

MVP 세로 슬라이스:
> 회원/권한 → 주소 표준화 → 매물 등록 → (배치) 외부 실거래가 수집 → 시세 기준 후보 생성(p10~p90/IQR) → 관리자 승인 → 매물 가격 리스크 자동 검증 → 관리자 매물 승인 → 승인 매물 조건 검색

## 2. 범위 (Scope)

### In Scope (1차)
- 회원가입/로그인(JWT), 역할(USER / REALTOR / ADMIN) 접근 제어, 내 정보 조회
- 행정안전부 주소 API(WebClient) 기반 주소 검색·표준화, `regionCode`/`regionName` 추출
- 매물 등록(DRAFT)/수정/삭제/상세/목록, 검증 요청(submit)
- 국토교통부 오피스텔 전월세 실거래가 API(WebClient) **배치 수집** (스케줄 + 수동 실행)
- `ExternalApiCallLog`, `PriceStandardBatchJob` 기록, `PARTIAL_FAILED` 지원
- `PriceStandardCandidate` 생성(p10~p90 또는 IQR, 최소 표본 정책), 관리자 승인/반려
- 승인 시 기존 `ACTIVE → EXPIRED`, 후보 → 신규 `ACTIVE`, `PriceStandardHistory` 기록
- 매물 자동 검증: 필수정보/이미지/설명길이/주소·지역 일치/가격 이상치/중복 의심 → `riskLevel` + `reason` 저장
- 관리자 매물 승인/반려 (`ACTIVE` / `REJECTED`)
- 승인(ACTIVE) 매물 QueryDSL 조건 검색·상세 조회

### Out of Scope (후속 차수 — ROADMAP 참조)
- 방문 슬롯/Redis 대기열/예약권/Mock 결제 (2차)
- 예약 취소/환불/월별 정산 (3차)
- Outbox Pattern/알림 비동기 처리 (4차). **1차에서는 승인/반려 후속작업을 동일 트랜잭션 동기 처리로 두되 도메인 로직과 분리.**
- Elasticsearch 키워드 검색 (5차). 1차 검색은 QueryDSL 조건 검색만.
- 인기 매물 캐싱/조회수 카운팅, 모니터링/배포 (6차)

## 3. 사용자 시나리오 (User Stories)

### US-1 중개사 매물 등록·검증 요청
중개사는 주소를 검색해 표준 주소를 선택하고(→ regionCode 확정), 매물 정보를 DRAFT 로 저장한 뒤 검증을 요청한다. 요청 즉시 서버가 자동 검증을 수행해 `riskLevel` 과 검증 사유를 남기고 상태를 `PENDING` 으로 바꾼다.

### US-2 관리자 시세 기준 관리
관리자는 실거래가 수집 배치를 실행(또는 스케줄)하고, 생성된 `PriceStandardCandidate(PENDING)` 목록을 검토한다. 표본이 충분한 후보를 승인하면 해당 지역·유형·거래유형의 운영 기준(`PriceStandard ACTIVE`)이 교체되고 이력이 남는다.

### US-3 관리자 매물 검증 처리
관리자는 `riskLevel=HIGH` 우선으로 검증 대기 매물과 검증 사유를 확인하고 승인/반려한다. 승인 매물만 `ACTIVE` 가 되어 검색에 노출된다.

### US-4 사용자 매물 검색
사용자는 지역/거래유형/매물유형/가격범위/면적/방개수 등 조건으로 `ACTIVE` 매물을 검색하고 상세를 조회한다.

## 4. 기능 요구사항 (Functional Requirements)

### 인증/권한
- **FR-001** 이메일+비밀번호로 회원가입한다. 비밀번호는 단방향 해시(BCrypt) 로 저장한다.
- **FR-002** 로그인 시 JWT access token 을 발급한다. (refresh/블랙리스트는 6차. MVP 는 access token + 만료.)
- **FR-003** API 는 역할(USER/REALTOR/ADMIN)로 접근을 제어한다. REALTOR 는 회원가입 시 또는 승격 절차로 부여한다(MVP: 가입 시 role 지정 허용).

### 주소 표준화
- **FR-010** 키워드로 주소 검색 시 WebClient 로 행정안전부 주소 API 를 호출해 표준 주소 후보 목록을 반환한다.
- **FR-011** 각 후보에서 `roadAddress`, `regionCode`(법정동코드), `regionName` 을 추출해 제공한다. 실거래가 조회에는 법정동코드 앞 5자리(시군구)를 사용한다.
- **FR-012** 주소 API 호출은 timeout/재시도 정책을 가지며 결과를 `ExternalApiCallLog` 에 남긴다. 실패 시 사용자에게 명확한 오류를 반환하되 서버 오류로 전파하지 않는다.

### 매물
- **FR-020** 중개사는 매물을 DRAFT 로 저장/수정/삭제한다. 본인 매물만 수정·삭제 가능하다.
- **FR-021** 매물은 `regionCode`, `propertyType(OFFICETEL 등)`, `dealType(JEONSE/MONTHLY_RENT)`, `deposit`, `monthlyRent`, `area`, `roomCount`, 대표 이미지, 설명을 가진다.
- **FR-022** `POST submit` 시 자동 검증을 수행하고 매물을 `PENDING`, 검증 결과를 저장한다. DRAFT 가 아닌 상태에서의 submit 은 거부한다.
- **FR-023** 매물 상태: `DRAFT → PENDING → ACTIVE | REJECTED`, 그리고 `ACTIVE → CLOSED/HIDDEN`, 모든 상태 → `DELETED`(soft).

### 외부 실거래가 배치 & 시세 기준
- **FR-030** 스케줄러가 매월 1일 03:00 에 수집 대상 `regionCode` 목록에 대해 최근 N개월(기본 3개월) 오피스텔 전월세 실거래가를 수집한다. 관리자 수동 실행 API 도 제공한다.
- **FR-031** 수집은 지역별로 WebClient 를 사용하되 **bounded concurrency (`concurrency=N`)** 로 병렬 호출한다. XML 응답을 DTO 로 파싱 후 JPA 저장으로 넘긴다.
- **FR-032** 배치는 `PriceStandardBatchJob`(RUNNING/SUCCESS/PARTIAL_FAILED/FAILED)과 지역별 `ExternalApiCallLog` 를 남긴다. 일부 지역 실패 시 전체를 실패시키지 않고 `PARTIAL_FAILED` 로 두고 해당 지역만 재수집 가능하다.
- **FR-033** 수집 표본을 지역·매물유형·거래유형별로 그룹핑하고, 보증금/월세에 대해 **p10~p90 또는 IQR** 기반 정상 범위를 산출해 `PriceStandardCandidate(PENDING)` 를 생성한다. `sampleCount`, `calculatedMonth`, `source` 를 기록한다.
- **FR-034** 표본 수가 `MIN_SAMPLE_COUNT`(설정값, 기본 30) 미만이면 후보를 생성하되 `dataStatus=INSUFFICIENT_DATA` 로 표시하고, 관리자 검토 없이는 검증에 사용하지 않는다.
- **FR-035** 관리자가 후보를 승인하면: 동일 (region, propertyType, dealType) 의 기존 `ACTIVE → EXPIRED(effectiveTo=now)` 처리, 후보 값으로 신규 `PriceStandard(ACTIVE, effectiveFrom=now)` 생성, `PriceStandardHistory` 에 이전/신규 값 기록. 반려 시 후보만 `REJECTED`.
- **FR-036** 관리자는 배치 실행 이력, 후보 목록(status 필터), 외부 API 호출 이력, ACTIVE 기준 목록을 조회한다.

### 매물 자동 검증
- **FR-040** submit 시 다음을 확인해 사유(reasonType)를 생성한다: `MISSING_REQUIRED_FIELD`, `MISSING_IMAGE`, `DESCRIPTION_TOO_SHORT`, `PRICE_OUT_OF_RANGE`, `ADDRESS_REGION_MISMATCH`, `DUPLICATE_SUSPECTED`.
- **FR-041** 가격 이상치는 매물의 (regionCode, propertyType, dealType) 로 `PriceStandard(ACTIVE)` 를 조회해 판정한다. 기준 범위를 벗어나면 `PRICE_OUT_OF_RANGE` 를 남기고 벗어난 정도로 riskLevel 을 가중한다.
- **FR-042** 해당 기준이 없거나 기준의 표본이 부족(`INSUFFICIENT_DATA`)하면 가격으로 HIGH 판정하지 않는다. 검증 결과를 `REVIEW_REQUIRED` 로 두어 관리자 판단에 맡긴다. (Constitution III)
- **FR-043** `riskLevel ∈ {LOW, MEDIUM, HIGH}` 을 산정한다. LOW=필수정보 충분+가격 정상, MEDIUM=일부 정보 부족 또는 가격 소폭 이탈, HIGH=가격 대폭 이탈/주소 불일치/중복 의심.
- **FR-044** 검증 결과는 `PropertyVerification`(요청자/리뷰어/status/riskLevel/rejectedReason)과 다건 `PropertyVerificationReason`(reasonType/message) 으로 저장한다.
- **FR-045** 중복 의심은 동일 regionCode + 유사 주소 + 동일 dealType/가격 근접 매물 존재 여부로 판단한다(MVP: 단순 규칙 기반).

### 매물 승인/검색
- **FR-050** 관리자는 검증 대기 매물을 `riskLevel` 필터로 조회하고 승인/반려한다. 승인 시 `Property.status=ACTIVE`, `verificationStatus=APPROVED`. 반려 시 `verificationStatus=REJECTED` + `rejectedReason`.
- **FR-051** 사용자 검색은 `ACTIVE` 매물만 대상으로 QueryDSL 조건(지역/거래유형/매물유형/보증금·월세 범위/면적/방개수/정렬/페이지네이션)을 지원한다. DRAFT/PENDING/REJECTED/HIDDEN/CLOSED/DELETED 는 제외한다.

## 5. Clarifications

/clarify 세션에서 확정된 결정. 각 항목은 위 FR 및 constitution 에 반영됨.

### Session 2026-07-07
- Q: 대기열/결제/정산을 MVP에 포함하는가? → **A: 제외.** MVP 는 검증·검색까지. 2차/3차로 분리(ROADMAP).
- Q: 시세 기준 범위 계산 방식은? → **A: p10~p90 또는 IQR.** 단순 min/max 미사용. `MIN_SAMPLE_COUNT`(기본 30) 미달 시 `INSUFFICIENT_DATA`, 검증은 `REVIEW_REQUIRED`.
- Q: 기준이 없거나 표본 부족일 때 가격 검증은? → **A: HIGH 판정 금지.** `REVIEW_REQUIRED` 로 관리자에게 위임.
- Q: 외부 API 병렬 수집 표현 기준은? → **A: `Flux.flatMap(fn, concurrency=N)` bounded concurrency 구현 시에만 "비동기 병렬" 로 표기.**
- Q: 1차 검색은 무엇으로? → **A: QueryDSL 조건 검색만.** Elasticsearch 는 5차.
- Q: Outbox 는 MVP 포함? → **A: 제외(4차).** 승인/반려 후속작업은 동일 트랜잭션 동기 처리, 단 도메인 로직과 분리해 4차 전환 용이하게.
- Q: 매물 유형 범위는? → **A: MVP 는 OFFICETEL 전월세부터.** 확장 유형은 후속.
- Q: 성능/검색 수치 문서화? → **A: 실측 전 미기재(Constitution VII).**

## 6. Key Entities (개요, 상세는 data-model.md)
User, Realtor, Property, PropertyVerification, PropertyVerificationReason, PriceStandard, PriceStandardCandidate, PriceStandardHistory, PriceStandardBatchJob, ExternalApiCallLog, (참고 이미지: PropertyImage).

## 7. 인수 기준 (Acceptance)
- [ ] 중개사가 주소 검색→매물 DRAFT→submit 하면 riskLevel/사유가 저장되고 상태가 PENDING 이 된다.
- [ ] 배치 실행 시 지역별 호출이 ExternalApiCallLog 에 남고, 일부 실패 시 BatchJob 이 PARTIAL_FAILED 로 남는다.
- [ ] 후보는 p10~p90/IQR 로 계산되며, 표본 부족 지역은 INSUFFICIENT_DATA 로 표시되고 HIGH 판정에 쓰이지 않는다.
- [ ] 후보 승인 시 기존 ACTIVE 가 EXPIRED 되고 신규 ACTIVE 와 History 가 생성된다.
- [ ] 관리자가 매물 승인 시에만 ACTIVE 가 되고, 검색은 ACTIVE 매물만 반환한다.
- [ ] 기준이 없는 지역의 매물은 가격으로 HIGH 판정되지 않는다(REVIEW_REQUIRED).

## 8. 위험/미해결 (Risks / [NEEDS CLARIFICATION])
- 국토부/주소 API 인증키 발급 및 rate limit 정책 확인 필요.
- 오피스텔 전월세 API 응답 필드(보증금/월세 단위, 결측치) 실제 스키마 확인 필요 → 파싱 DTO 는 실 응답 기준으로 확정.
- `regionCode` 표준(법정동코드 10자리 vs 시군구 5자리) 사용 규칙 문서 고정 필요(현재: 저장은 법정동, 실거래가 조회는 앞 5자리).
