# Implementation Plan: Elasticsearch nori 한글 검색 (5차)

- Branch prefix: `feat/005-p<phase>-*`
- Spec: `./spec.md` (§2 확정 결정)
- Constitution: v1.0.0

## Summary
매물 승인/수정/비활성 시 도메인 트랜잭션에서 `OutboxEvent(PROPERTY_INDEX/UNINDEX)` 를 적재(4차 재사용).
Worker 를 **핸들러 레지스트리**로 일반화해 색인 이벤트는 `PropertyIndexHandler` 가 ES 에 upsert/delete.
검색은 신규 `GET /api/properties/search` 가 nori 전문검색 + 필터 + 관련도(multi_match 부스팅)로 처리.

## Constitution Check
| 원칙 | 준수 |
| --- | --- |
| IV. Outbox | 색인을 도메인 트랜잭션과 분리, 4차 Outbox/재시도/멱등 재사용 |
| VI. 차수 분리 | ES 는 5차에서 도입. 기존 QueryDSL 검색은 유지 |
| VII. 검색 품질 | nori decompound + 필드 부스팅. latency 아닌 관련도 근거 |
| VIII. 상태전이 테스트 | 색인/삭제/재색인, 검색 관련도/필터 테스트 |

## 핵심 흐름

### D1. Worker 라우팅 일반화 (4차 리팩터)
- `OutboxEventHandler` 인터페이스: `boolean supports(String eventType)`, `void handle(OutboxEvent)`.
- 기존 알림 처리 → `NotificationOutboxHandler`(NotificationType 이벤트 supports). 색인 → `PropertyIndexOutboxHandler`.
- `OutboxWorker.publishOne` 은 dispatcher 대신 **핸들러 목록에서 supports 되는 핸들러**로 위임(없으면 예외 → 재시도/DEAD).
- 소비 멱등은 핸들러별로: 알림=notification UNIQUE, 색인=ES upsert(id=propertyId) 자연 멱등.

### D2. 색인 이벤트 적재 (Producer)
- 매물 승인(`PropertyVerificationAdminService.approve` → 이미 ACTIVE): `outbox.append("PROPERTY", propertyId, "PROPERTY_INDEX", "PROPERTY_INDEX:{propertyId}:{updatedAtMillis}", {propertyId})`.
- 매물 수정(`PropertyService.update`, ACTIVE 일 때): 동일 PROPERTY_INDEX(updatedAt 갱신 → 키 달라짐).
- 비활성(reject/close 등 ACTIVE 이탈): `PROPERTY_UNINDEX:{propertyId}:{updatedAtMillis}`, payload {propertyId}.
- payload 는 propertyId 만(핸들러가 최신 매물 재조회) → 문서 최신성 보장.

### D3. 색인 핸들러 (Consumer)
- `PropertyIndexOutboxHandler.handle`:
  - PROPERTY_INDEX → `propertyRepository.findById` → ACTIVE 이면 `PropertyDocument` 변환 후 `propertyDocumentRepository.save`(upsert), 아니면 delete(방어).
  - PROPERTY_UNINDEX → `propertyDocumentRepository.deleteById(propertyId)`.
- ES 예외 → 핸들러가 던짐 → Worker 재시도/백오프(4차).

### D4. 검색 (Query)
- `PropertySearchEsService.search(q, filters, pageable)`:
  - `q` 있으면 `multi_match`(fields: title^3, nearStation^2, regionName^2, description) + nori analyzer.
  - 필터: `status=ACTIVE`(항상) + term(sigunguCode/dealType/propertyType) + range(deposit/rent/area) + term(roomCount).
  - 정렬: q 있으면 `_score` desc, 없으면 createdAt desc.
  - Spring Data ES `NativeQuery`(bool: must=multi_match, filter=필터들).
- 결과는 기존 `PropertySummaryResponse` 재사용 매핑.

### D5. 인덱스 매핑/분석기
- `property` 인덱스 settings: analyzer `korean_nori`(tokenizer nori, decompound_mode=mixed, filter: nori_part_of_speech, lowercase).
- mappings: text 필드는 korean_nori, keyword 필드는 keyword, 숫자 필드는 long/double.
- 애플리케이션 기동 시 인덱스 없으면 생성(매핑 JSON 리소스) — Spring Data ES `IndexOperations` 또는 부트스트랩 러너.

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

## 인프라 (신규)
- `spring-boot-starter-data-elasticsearch` 의존성.
- **ES + nori 이미지**: 커스텀 Dockerfile(`docker/elasticsearch-nori/`) 로 `elasticsearch:8.x` + `bin/elasticsearch-plugin install analysis-nori`.
- docker-compose 에 elasticsearch 서비스 추가. application.yml `spring.elasticsearch.uris`.
- 테스트: Testcontainers `ElasticsearchContainer`(nori 이미지). `@ConditionalOnProperty` 로 검색 테스트 격리 불필요(색인은 핸들러 직접 호출).

## Testing Strategy
- 통합(Testcontainers ES+MySQL): 매물 승인→Outbox→핸들러 색인→검색 노출, 수정 재색인, 비활성 제외.
- 관련도: "강남역 오피스텔" 등 질의로 형태소 매칭·필드 부스팅 순위 검증. 복합어 decompound("역세권").
- 필터+q 조합, 기존 QueryDSL 검색 회귀(영향 없음).
- Worker 라우팅: 알림 이벤트와 색인 이벤트가 각각 올바른 핸들러로 감.

## Phasing
1. ES 인프라(의존성·nori 이미지·docker-compose) + PropertyDocument/인덱스 매핑 + 부트스트랩
2. Worker 핸들러 레지스트리 리팩터(NotificationOutboxHandler 로 이관, 회귀 유지)
3. 색인 이벤트 적재(승인/수정/비활성) + PropertyIndexOutboxHandler
4. 검색 서비스/엔드포인트(multi_match 부스팅 + 필터)
5. 통합 E2E(색인→검색 품질/관련도) + docs 마감

## Complexity Tracking
- ES 도입(신규 인프라) — 원칙 VI 에 따라 5차에서만. Worker 리팩터는 4차 구조를 확장(핸들러 분리)해 결합도↓.
