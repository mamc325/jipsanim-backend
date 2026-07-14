# Implementation Plan: Elasticsearch nori 한글 검색 (5차)

- Branch prefix: `feat/005-p<phase>-*`
- Spec: `./spec.md` (§2 확정 결정)
- Constitution: v1.0.0

## Summary
매물 **승인/비활성(ACTIVE 진입/이탈)** 시 도메인 트랜잭션에서 `OutboxEvent(PROPERTY_INDEX/UNINDEX)` 를 적재(4차 재사용).
Worker 를 **핸들러 레지스트리**로 일반화해 색인 이벤트는 `PropertyIndexOutboxHandler` 가 ES 에 upsert/delete.
검색은 신규 `GET /api/properties/search` 가 nori 전문검색 + 필터 + 관련도(multi_match 부스팅)로 처리.

## Constitution Check
| 원칙 | 준수 |
| --- | --- |
| IV. Outbox | 색인을 도메인 트랜잭션과 분리, 4차 Outbox/재시도/멱등 재사용 |
| VI. 차수 분리 | ES 는 5차에서 도입. 기존 QueryDSL 검색은 유지 |
| VII. 검색 품질 | nori decompound + 필드 부스팅. latency 아닌 관련도 근거 |
| VIII. 상태전이 테스트 | 색인(승인)/삭제(비활성), 검색 관련도/필터 테스트 |

## 핵심 흐름

### D1. Worker 라우팅 일반화 (4차 리팩터)
- `OutboxEventHandler` 인터페이스: `boolean supports(String eventType)`, `void handle(OutboxEvent)`.
- 기존 알림 처리 → `NotificationOutboxHandler`(NotificationType 이벤트 supports). 색인 → `PropertyIndexOutboxHandler`.
- `OutboxWorker.publishOne` 은 dispatcher 대신 **핸들러 목록에서 supports 되는 핸들러**로 위임(없으면 예외 → 재시도/DEAD).
- 소비 멱등은 핸들러별로: 알림=notification UNIQUE, 색인=ES upsert(id=propertyId) 자연 멱등.

### D2. 색인 이벤트 적재 (Producer) — ACTIVE 진입/이탈 기준(리뷰 P1)
- **판정 규칙(안전)**: `prev != ACTIVE && new == ACTIVE` → `PROPERTY_INDEX`, `prev == ACTIVE && new != ACTIVE` → `PROPERTY_UNINDEX`. 그 외 전이는 이벤트 없음.
  - 이렇게 하면 `PENDING→REJECTED`(색인된 적 없음)엔 UNINDEX 안 붙고, `ACTIVE→HIDDEN` 도 자동으로 UNINDEX 된다(enum 목록 나열 회피).
- 색인 append 는 **`PropertyIndexEventRecorder`** 를 통함(리뷰 P1): `search.elasticsearch.enabled=true` 일 때만 append, false 면 no-op → 색인 disabled 시 고아 이벤트/DEAD 누적 없음. 도메인 서비스는 이 recorder 만 호출.
- 승인(`PropertyVerificationAdminService.approve`, PENDING→ACTIVE): **기존 `PROPERTY_APPROVED` 알림 이벤트는 그대로 유지**하고 recorder 로 `PROPERTY_INDEX:{propertyId}:{gen}` **추가 append**(대체 아님 — 4차 알림 유지).
- 비활성(softDelete 등 ACTIVE→그 외): recorder 로 `PROPERTY_UNINDEX:{propertyId}:{gen}` append.
- **`{gen}` = append 시점 UUID(리뷰 P1)**: 상태값만 키로 쓰면 반복 전이(ACTIVE↔HIDDEN)에서 `event_key` UNIQUE 로 두 번째 UNINDEX 가 no-op 유실 → 전이마다 새 이벤트. `updatedAt`(Auditing flush)은 안 씀. ES upsert/delete 가 멱등이라 최종 1건.
- payload 는 propertyId 만(핸들러가 최신 매물 재조회) → 문서 최신성 보장.

### D3. 색인 핸들러 (Consumer, `@ConditionalOnProperty(search.elasticsearch.enabled)`)
- `PropertyIndexOutboxHandler.handle`:
  - PROPERTY_INDEX → `propertyRepository.findById` → ACTIVE 이면 `PropertyDocument`(대표 이미지 `primaryImageUrl` 포함) 변환 후 `propertyDocumentRepository.save`(upsert), 아니면 delete(방어).
  - PROPERTY_UNINDEX → `propertyDocumentRepository.deleteById(propertyId)`.
- ES 예외 → 핸들러가 던짐 → Worker 재시도/백오프(4차).

### D4. 검색 (Query)
- `PropertySearchEsService.search(q, filters, pageable)`:
  - `q` 있으면 `multi_match`(fields: title^3, nearStation^2, regionName^2, description) + nori analyzer.
  - 필터: `status=ACTIVE`(항상) + term(sigunguCode/dealType/propertyType) + range(deposit/monthlyRent/area) + term(roomCount).
  - 정렬(tie-breaker, 리뷰 P2): q 있으면 `_score desc, createdAt desc, propertyId desc`; 없으면 `createdAt desc, propertyId desc`. (문자열 keyword `_id` 대신 **숫자 `propertyId`** 로 정렬 — 리뷰 P1)
  - Spring Data ES `NativeQuery`(bool: must=multi_match, filter=필터들).
- 결과는 기존 `PropertySummaryResponse` 재사용 매핑.

### D5. 인덱스 매핑/분석기
- **Spring Data 자동 생성 차단(리뷰 P1)**: `@Document(indexName="property", createIndex = false)` — Spring Data 가 기본(nori 미적용) 매핑으로 인덱스를 먼저 만들지 못하게 한다.
- `PropertyIndexBootstrap`(ApplicationRunner, **`@ConditionalOnProperty(search.elasticsearch.enabled)`**)이 **settings/mapping 생성 책임**: 인덱스 없으면 JSON 리소스로 생성. **일반 테스트(enabled=false)에서는 빈이 아예 안 떠서 ES 없이 기동**(기존 @SpringBootTest 보호, 리뷰 P1).
- settings: **named filter `korean_pos_filter`(nori_part_of_speech)** 를 정의하고 analyzer `korean_nori`(tokenizer nori decompound=mixed, filter: korean_pos_filter, lowercase)에서 참조(리뷰 P2). mappings: text=korean_nori, keyword=keyword, 숫자=long/double.

## Architecture / Package
```
com.jipsanim.search
├─ document    (PropertyDocument @Document(indexName="property"))
├─ repository  (PropertyDocumentRepository extends ElasticsearchRepository)
├─ index       (PropertyIndexOutboxHandler, PropertyIndexBootstrap: 매핑 생성)
├─ query       (PropertySearchEsService, PropertyEsSearchCondition)
└─ controller  (PropertySearchController: GET /api/properties/search)

com.jipsanim.outbox.worker (리팩터)
├─ OutboxEventHandler (신규 인터페이스)
└─ OutboxWorker (핸들러 레지스트리 위임)
com.jipsanim.notification.dispatch
└─ NotificationOutboxHandler (기존 Dispatcher 를 핸들러로 감쌈)
```

## 인프라 (신규, 리뷰 P2)
- `spring-boot-starter-data-elasticsearch` 의존성.
- **ES + nori 이미지**: 커스텀 Dockerfile(`docker/elasticsearch-nori/`) — **버전 pin**(예 `elasticsearch:8.13.4`) + `elasticsearch-plugin install analysis-nori`.
- docker-compose elasticsearch 서비스 env(로컬 단일 노드, 보안 off):
  ```
  discovery.type=single-node
  xpack.security.enabled=false
  ES_JAVA_OPTS=-Xms512m -Xmx512m
  ```
  application.yml `spring.elasticsearch.uris=http://localhost:9200`.
- **`search.elasticsearch.enabled`(리뷰 P1)**: 운영 yml=true, 공통 테스트=false. Bootstrap/PropertyIndexOutboxHandler/PropertySearchService·Controller 를 `@ConditionalOnProperty` 로 게이팅.
  - **ES Repository 빈도 차단(리뷰 P1)**: `@EnableElasticsearchRepositories` 를 별도 `@Configuration(ElasticsearchRepositoryConfig)` 로 빼서 `@ConditionalOnProperty(search.elasticsearch.enabled)` 로만 켠다. 공통 테스트 yml 에 `spring.data.elasticsearch.repositories.enabled=false` 도 함께 둔다 → 일반 @SpringBootTest 에서 ES 리포지토리/연결 빈 미생성.
- 테스트: `search.elasticsearch.enabled=true` + Testcontainers `ElasticsearchContainer`(nori 이미지, 동일 버전 pin)로 ES 통합 테스트만 격리. 색인은 핸들러 직접 호출로 결정적 검증.

## Testing Strategy
- 통합(Testcontainers ES+MySQL): 매물 승인→Outbox→핸들러 색인→검색 노출, 비활성→삭제→검색 제외.
- 관련도: "강남역 오피스텔" 등 질의로 형태소 매칭·필드 부스팅 순위 검증. 복합어 decompound("역세권").
- 필터+q 조합, 기존 QueryDSL 검색 회귀(영향 없음).
- Worker 라우팅: 알림 이벤트와 색인 이벤트가 각각 올바른 핸들러로 감.

## Phasing
1. ES 인프라(의존성·nori 이미지·docker-compose) + PropertyDocument/인덱스 매핑 + 부트스트랩
2. Worker 핸들러 레지스트리 리팩터(NotificationOutboxHandler 로 이관, 회귀 유지)
3. 색인 이벤트 적재(승인/비활성, 기존 알림 이벤트 유지) + PropertyIndexOutboxHandler
4. 검색 서비스/엔드포인트(multi_match 부스팅 + 필터)
5. 통합 E2E(색인→검색 품질/관련도) + docs 마감

## Complexity Tracking
- ES 도입(신규 인프라) — 원칙 VI 에 따라 5차에서만. Worker 리팩터는 4차 구조를 확장(핸들러 분리)해 결합도↓.
