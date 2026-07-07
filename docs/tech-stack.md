# 기술 스택 선정 문서 (집사님)

Constitution v1.0.0 의 Technology Constraints 를 "왜 골랐는가 / 대안은 무엇이었는가" 관점으로 정리한다.
개별 결정은 ADR 형식으로 관리하고, 이 문서는 요약 인덱스 역할을 한다.

## 요약 표

| 영역 | 선택 | 주요 대안 | 선택 이유(한 줄) |
| --- | --- | --- | --- |
| 언어 | Java 21 (LTS) | Java 17, Kotlin | LTS + Virtual Thread/record/pattern, 팀 친숙도 |
| 프레임워크 | Spring Boot 3.x (MVC) | WebFlux 전면, Micronaut | 트랜잭션 정합성 우선, JPA 블로킹과 정합 |
| 영속성 | Spring Data JPA + QueryDSL | MyBatis, jOOQ | 도메인 중심 + 타입세이프 동적 검색 |
| DB | MySQL 8 | PostgreSQL | 트랜잭션·UNIQUE·월별 집계, 운영 친숙도 |
| 외부 API | WebClient (경계 한정) | RestClient, RestTemplate | bounded concurrency 병렬 수집 |
| 인증 | Spring Security + JWT | 세션, OAuth2 | 무상태 REST, 역할 기반 인가 |
| 비동기 후속 | Outbox(4차) + Spring Scheduler | Kafka, RabbitMQ | DB 트랜잭션과 원자적, 인프라 최소 |
| 대기열(2차) | Redis Sorted Set + TTL | DB 폴링, Kafka | 순번/TTL 예약권에 자연 적합 |
| 검색(5차) | Elasticsearch + nori | DB LIKE, MySQL FT | 한글 형태소 검색 품질 |
| 테스트 | JUnit5, Mockito, Testcontainers | H2, 순수 Mock | 실 DB/Redis 통합 신뢰성 |
| 배포(6차) | Docker, GitHub Actions, AWS | 수동 배포 | 재현 가능한 파이프라인 |

---

## ADR-001. 전면 WebFlux 대신 Spring MVC + JPA
- **결정**: 애플리케이션 본체는 MVC + JPA. 반응형(WebClient/Mono/Flux)은 외부 API Client 경계 안에서만.
- **이유**: 예약·결제·정산은 트랜잭션·정합성이 핵심. JPA 는 블로킹이라 WebFlux 와 상성이 나쁘고, 무분별한 `.block()` 은 안티패턴.
- **트레이드오프**: 초당 커넥션 극한 확장성은 포기. 대신 코드 단순성·정합성·디버깅 용이성 확보.
- **적용**: Constitution 원칙 V.

## ADR-002. 외부 API는 WebClient로 배치 격리
- **결정**: 실거래가는 스케줄 배치로만 수집, 검증은 내부 `PriceStandard(ACTIVE)` 만 조회. 주소 API 는 등록 흐름에서만.
- **이유**: 외부 장애/timeout 이 핵심 요청으로 전파되면 안 됨. WebClient 는 timeout/retry/`flatMap(concurrency=N)` 병렬 수집을 경계에 모으기 좋음.
- **대안**: RestClient(동기, 간단하지만 병렬 수집 표현 약함), RestTemplate(유지보수 모드).
- **적용**: Constitution 원칙 I, V / plan D1.

## ADR-003. 검색 계층 분리 — QueryDSL(1차) → Elasticsearch(5차)
- **결정**: 1차는 QueryDSL 조건 검색. 키워드/한글 형태소 검색은 5차에서 ES+nori.
- **이유**: 조건 검색은 RDB 로 충분. ES 는 "강남역 풀옵션 오피스텔" 같은 형태소 기반 관련도 검색에서 가치. **latency 수치가 아니라 검색 품질**로 어필(Constitution VII).
- **트레이드오프**: 색인/동기화 복잡도는 5차로 미룸.

## ADR-004. 비동기 후속처리 — 메시지 브로커 대신 Outbox(4차)
- **결정**: 알림/색인은 같은 트랜잭션에서 `outbox_events` 기록 후 Worker 처리. MVP 는 도메인 이벤트(`ApplicationEventPublisher`)로 발행만 해 두고 4차에 Outbox 리스너로 교체.
- **이유**: 브로커(Kafka/RabbitMQ) 없이 DB 트랜잭션과 원자적으로 이벤트 확보 → 유실/중복 방지. 개인 프로젝트 인프라 최소화.
- **대안**: Kafka(강력하나 인프라·운영 비용 큼) — 본 프로젝트 목표(정합성 시연)엔 과함.
- **적용**: Constitution 원칙 IV.

## ADR-005. 대기열 — Redis Sorted Set + TTL (2차)
- **결정**: `waiting:visit-slot:{slotId}` ZSet 순번 + 슬롯 단위 예약권 키 TTL. 발급은 Lua/`SET NX`+`ZPOPMIN` 원자화.
- **이유**: score 기반 순번, TTL 만료 자동화가 대기열/예약권에 자연 적합. 슬롯당 active token 1개 보장.
- **적용**: Constitution 원칙 II / specs/002.

## ADR-006. MySQL 부분 유니크 대체 (active_key)
- **결정**: MySQL 은 partial unique index 미지원 → ACTIVE 일 때만 값이 채워지는 생성 컬럼 `active_key` 에 UNIQUE.
- **이유**: (region, type, deal) ACTIVE 기준 유일성을 DB 레벨 최종 방어선으로 보장.
- **적용**: data-model §7 / plan D3.

## 버전/도구 (고정됨)
- JDK 21, Gradle 8.14.x (Kotlin DSL), **Spring Boot 3.5.16**
  - Boot 4.1.0(Framework 7)이 Initializr 기본값이나, QueryDSL/springdoc/한글 학습자료 호환을 위해 3.x 안정 최신(3.5.16) 채택.
- QueryDSL 5.1.0 (jakarta), springdoc-openapi 2.8.9 (OpenAPI 자동 생성)
- jjwt 0.12.6 (JWT), okhttp MockWebServer 4.12.0 (외부 API 테스트)
- MySQL 8.x, Redis 7.x(2차), Elasticsearch 8.x + nori(5차)
- Testcontainers, MockWebServer, k6(6차)
