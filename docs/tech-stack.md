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
| 캐시/조회수(6차) | Redis(원자 Lua·writeback·ZSET 감쇠) | DB 카운터, @Cacheable | 부하 완화 + 정합성(유실 0)·트렌딩 |
| 관측성(6차) | Micrometer/Prometheus·Grafana·Sentry | ELK, APM 유료 | 메트릭·대시보드·에러추적, 로컬 자족 |
| 테스트 | JUnit5, Mockito, Testcontainers | H2, 순수 Mock | 실 DB/Redis 통합 신뢰성 |
| 배포(6차) | Docker 멀티스테이지, docker-compose, GitHub Actions | 수동 배포 | 재현 가능한 파이프라인(이미지 빌드까지) |

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

## ADR-003. 검색 계층 분리 — QueryDSL(1차) → Elasticsearch(5차, ✅ 구현 완료)
- **결정**: 1차는 QueryDSL 조건 검색(`GET /api/properties`). 키워드/한글 형태소 검색은 5차에서 ES+nori(`GET /api/properties/search`, 별도 엔드포인트로 공존).
- **이유**: 조건 검색은 RDB 로 충분. ES 는 "강남역 풀옵션 오피스텔" 같은 형태소 기반 관련도 검색에서 가치. **latency 수치가 아니라 검색 품질**로 어필(Constitution VII).
- **구현**: `korean_nori` analyzer(`nori_tokenizer decompound_mode=mixed` + `korean_pos_filter`), `multi_match` 필드 부스팅(`title^3`·`nearStation^2`·`regionName^2`·`description`), `status=ACTIVE` 강제, tie-breaker(`_score→createdAt→propertyId`). **색인 동기화는 ADR-004 Outbox 재사용**(`PROPERTY_INDEX`/`PROPERTY_UNINDEX`)으로 DB↔ES 정합성을 커밋 원자성+이중 멱등으로 보장.
- **degrade**: ES 장애 시 `SEARCH_UNAVAILABLE`(503) — 검색만 격리, 기존 QueryDSL 경로 무영향(`@ConditionalOnProperty` 게이팅).
- **발견/트레이드오프**: `korean_pos_filter` stoptags(XSN)로 접미사가 제거돼 `역세권`→`[역세]` 로 복합어 원형 미보존 → 검색어로 부적합. decompound 는 `전력→한국전력공사` 로 검증. 개선안은 user_dictionary 등록/XSN 제외(`specs/005-search-elasticsearch/spec.md §8`).

## ADR-004. 비동기 후속처리 — 메시지 브로커 대신 Outbox(4차)
- **결정**: 알림/색인은 도메인 서비스가 **같은 `@Transactional` 안에서 `OutboxEventPublisher.append()` 로 직접** `outbox_event` 기록 → 폴링 Worker 처리(4차 확정, `specs/004-outbox-notification`). producer 멱등 `event_key` UNIQUE · consumer 멱등 `outbox_event_id` UNIQUE · `SKIP LOCKED` 선점 · PROCESSING reaper · 지수 백오프→DEAD.
  - **정정**: 초기 MVP 는 매물 승인/거절을 `ApplicationEventPublisher` 로 발행했으나 4차에서 **직접 append 로 통일**(AFTER_COMMIT 리스너는 원자성이 깨져 미채택). 기존 event/listener 는 4차에서 정리.
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

## ADR-007. 조회수/캐싱·관측성 — Redis 근사 카운터 + Micrometer (6차, ✅ 구현 완료)
- **결정**: 조회수는 요청마다 DB UPDATE 대신 **Redis 카운트(원자 Lua: dedup+HINCRBY+ZINCRBY) → 주기 writeback**(`RENAME` 원자 배출). 인기 목록/상세는 **직접 cache-aside**(RedisTemplate+JSON), 트렌딩 랭킹은 ZSET+일 감쇠(`ZUNIONSTORE WEIGHTS`).
- **이유**: DB 쓰기 부하 완화 + 정합성(배출 유실 0). `@Cacheable` 대신 직접 cache-aside 는 조립/부분 무효화·ACTIVE 조건부 저장·메트릭 계측이 명시적.
- **정합성 등급**: 조회수는 **근사 카운터**(유실 0 지향, 중복 가산 크래시 창 허용) — 예약/정산(원칙 II 엄격)과 구분. 인기 목록 정확성은 **DB ACTIVE 필터가 권위**, ZSET/캐시는 best-effort(2계층).
- **관측성/보안**: Micrometer→Prometheus(`/actuator/prometheus` permitAll 무인증, 외부 노출은 compose/프록시 차단) + Grafana + Sentry(빈 DSN=no-op). `management.prometheus.metrics.export.enabled: true` 명시 필요(폴백 off).
- **배포**: 멀티스테이지 Docker 이미지 + docker-compose 전 스택 + GitHub Actions(빌드·Testcontainers·이미지). 실 배포 제외.
- **적용**: Constitution 원칙 II·V·VII / specs/006.

## 버전/도구 (고정됨)
- JDK 21, Gradle 8.14.x (Kotlin DSL), **Spring Boot 3.5.16**
  - Boot 4.1.0(Framework 7)이 Initializr 기본값이나, QueryDSL/springdoc/한글 학습자료 호환을 위해 3.x 안정 최신(3.5.16) 채택.
- QueryDSL 5.1.0 (jakarta), springdoc-openapi 2.8.9 (OpenAPI 자동 생성)
- jjwt 0.12.6 (JWT), okhttp MockWebServer 4.12.0 (외부 API 테스트)
- MySQL 8.x, Redis 7.x(2차), Elasticsearch 8.x + nori(5차)
- Testcontainers, MockWebServer, k6(6차)
