# Implementation Plan: 실거래가 기반 시세 검증 MVP

- Branch: `001-price-verification-mvp`
- Spec: `./spec.md`
- Constitution: v1.0.0

## Summary
Spring MVC + JPA 모놀리식으로 구성한다. 외부 API(주소/실거래가)는 WebClient 경계 계층으로 격리하고, 실거래가는 스케줄 배치로만 수집한다. 시세 기준은 IQR/백분위로 계산해 후보→관리자 승인→ACTIVE 반영한다. 매물 검증은 내부 ACTIVE 기준만으로 가격 리스크를 판정한다. 검색은 QueryDSL 조건 검색.

## Technical Context
- Language: Java 21, Spring Boot 3.x
- Web: Spring MVC (REST)
- Persistence: Spring Data JPA + QueryDSL, MySQL 8
- External client: Spring WebClient (reactor). 사용은 `external/` 패키지 경계 내부로 한정.
- Security: Spring Security + JWT (access token)
- Batch: Spring `@Scheduled` + 수동 트리거 서비스
- Test: JUnit5, Mockito, Spring Boot Test, Testcontainers(MySQL), WireMock/MockWebServer(외부 API)
- Config: `MIN_SAMPLE_COUNT`(기본 30), `COLLECT_MONTHS`(기본 3), `WEBCLIENT_CONCURRENCY`(기본 8), timeout/retry 값은 프로퍼티화

## Constitution Check
| 원칙 | 준수 방법 |
| --- | --- |
| I. 외부 API 격리 | 검증/검색은 내부 테이블만 조회. WebClient 는 배치·주소검색에만. 모든 호출 `ExternalApiCallLog` 기록 |
| II. 멱등성/원자성 | 후보 승인·매물 승인은 현재 상태 조건부 전이. ACTIVE 유일성은 부분 유니크(아래) 로 방어 |
| III. 통계적 신뢰성 | IQR/백분위 계산 + MIN_SAMPLE_COUNT 게이트 + INSUFFICIENT_DATA/REVIEW_REQUIRED |
| IV. Outbox | MVP 미도입. 후속작업은 `ApplicationEventPublisher` 로 발행해 4차에서 Outbox 로 교체 용이하게 |
| V. 정합성 우선 | MVC+JPA 트랜잭션. WebClient 흐름은 DTO 변환 후 종료, JPA 로 인계 |
| VI. 차수 분리 | 본 plan 은 001 범위만 |
| VII. 측정 후 주장 | 수치 미기재 |
| VIII. 상태전이 테스트 | 검증 판정/후보 승인/매물 승인 상태전이 테스트 필수 |

위반 없음 → Complexity Tracking 비움.

## Architecture / Package Layout
```
com.jipsanim
├─ common            (config, security, error, response, jwt)
├─ user              (User, Realtor, auth)
├─ property          (Property, PropertyImage, 등록/수정/검색 QueryDSL)
│   └─ verification  (PropertyVerification, Reason, 자동검증 엔진, 관리자 승인/반려)
├─ pricestandard
│   ├─ domain        (PriceStandard, Candidate, History, BatchJob)
│   ├─ batch         (PriceStandardBatchJob 실행, 스케줄러, 수동 트리거)
│   └─ stats         (RangeCalculator: 백분위/IQR)
└─ external
    ├─ address       (AddressClient: 행정안전부 주소 API)
    ├─ molit         (RealEstateClient: 국토부 실거래가 API, XML 파싱)
    └─ log           (ExternalApiCallLog 기록 aspect/wrapper)
```

## Key Design Decisions

### D1. WebClient bounded concurrency 수집 (FR-031, Constitution V)
지역별 실거래가 호출을 실제 병렬로 제한 수행한다. 순차 block 반복 금지.
```java
Flux.fromIterable(targetRegions)                     // 수집 대상 regionCode
    .flatMap(region ->
        realEstateClient.fetch(region, yearMonth)     // Mono<RealEstateResponse>
            .timeout(TIMEOUT)
            .retryWhen(Retry.backoff(2, Duration.ofMillis(300)))
            .doOnEach(logCall(region))                // ExternalApiCallLog
            .onErrorResume(e -> Mono.just(RealEstateResponse.failed(region, e))),
        WEBCLIENT_CONCURRENCY)                         // concurrency=N (기본 8)
    .collectList()
    .block();                                          // 배치 경계에서만 block 허용
```
- 결과 리스트를 배치 서비스가 JPA 트랜잭션으로 저장. WebFlux 흐름을 서비스 밖으로 끌고 가지 않는다.
- 성공/실패 지역을 집계해 BatchJob status(SUCCESS/PARTIAL_FAILED/FAILED) 결정.

### D2. 시세 범위 계산기 RangeCalculator (FR-033~034, Constitution III)
- 입력: 지역·유형·거래유형별 표본(보증금/월세 배열).
- 방식(설정 택1, 기본 IQR):
  - **PERCENTILE**: [p10, p90]
  - **IQR**: [Q1 − 1.5·IQR, Q3 + 1.5·IQR] 로 이상치 제거 후 남은 표본의 [min, max] 또는 [p5, p95]
- `sampleCount < MIN_SAMPLE_COUNT` → 후보 생성하되 `dataStatus=INSUFFICIENT_DATA`.
- JEONSE 는 보증금만, MONTHLY_RENT 는 보증금+월세 각각 범위 산출.
- 순수 함수로 구현해 단위 테스트로 고정(경계·이상치·소표본 케이스).

### D3. 후보 승인 시 ACTIVE 교체 원자성 (FR-035, Constitution II)
단일 트랜잭션에서:
1. `SELECT ... FOR UPDATE` 로 동일 (region, propertyType, dealType) ACTIVE 잠금
2. 기존 ACTIVE → EXPIRED (effectiveTo=now)
3. 신규 ACTIVE insert (effectiveFrom=now)
4. History insert (previous/new)
5. Candidate → APPROVED
- 최종 방어선: **부분 유니크 인덱스** `UNIQUE(sigunguCode, propertyType, dealType) WHERE status='ACTIVE'`. MySQL 은 부분 인덱스 미지원 → 생성 컬럼 `active_key`(ACTIVE 일 때 `sigungu:type:deal`, 아니면 NULL)에 UNIQUE 부여. (data-model 참조)
- 후보의 `dataStatus` 를 신규 ACTIVE 기준에 상속한다(INSUFFICIENT_DATA 포함). 소표본 승인은 422 로 막지 않고 flag 로 표시해 검증 단계에서 REVIEW_REQUIRED 로 처리(D4).

### D4. 자동 검증 엔진 (FR-040~045)
- `VerificationRule` 인터페이스 목록을 순회하며 `List<Reason>` 누적 → riskLevel 산정기(RiskScorer)가 종합.
- 가격 규칙은 ACTIVE 기준 조회 결과가 없거나 INSUFFICIENT_DATA 면 HIGH 대신 `REVIEW_REQUIRED` 신호를 낸다.
- 규칙 추가가 쉬운 구조(중복 의심 등 후속 강화 대비).

### D5. 외부 호출 로깅
- `ExternalApiCallLog` 는 호출 wrapper(또는 AOP)에서 일괄 기록: apiType, url, params, status, success, elapsedMs, errorMessage. 배치/주소검색 공통.

### D6. 후속작업 분리(4차 대비)
- 매물 승인/반려 시 `PropertyApprovedEvent` 등 도메인 이벤트를 `ApplicationEventPublisher` 로 발행. MVP 는 리스너 없음/최소. 4차에서 Outbox 저장 리스너로 교체.

## Phasing (구현 순서)
1. 스캐폴딩 + 공통(보안/에러/응답) + User/Realtor + JWT
2. 주소 WebClient + 매물 CRUD + submit(검증 엔진 골격)
3. 실거래가 WebClient + 배치(bounded concurrency) + BatchJob/CallLog
4. RangeCalculator + Candidate 생성 + 관리자 승인(ACTIVE 교체/History)
5. 가격 규칙 연결 + riskLevel + 관리자 매물 승인/반려
6. QueryDSL 조건 검색 + 통합 테스트(Testcontainers)

## Testing Strategy
- 단위: RangeCalculator(IQR/백분위/경계/소표본), RiskScorer, 각 VerificationRule.
- 통합(Testcontainers MySQL): 후보 승인 ACTIVE 교체 유일성, 매물 submit→검증 저장, 검색 필터(ACTIVE only).
- 외부 API: MockWebServer 로 XML/JSON 응답 파싱·timeout·부분실패 검증. BatchJob PARTIAL_FAILED 재현.

## Complexity Tracking
(없음)
