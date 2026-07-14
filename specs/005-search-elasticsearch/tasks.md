# Tasks: Elasticsearch nori 한글 검색 (5차)

규칙: `[P]` 병렬 가능. 색인/검색은 테스트 먼저 가능한 것부터. 브랜치 `feat/005-p<phase>-*`.

## Phase 1. ES 인프라 + 인덱스 매핑
- [ ] T500 `spring-boot-starter-data-elasticsearch` 의존성 + `spring.elasticsearch.uris` 설정
- [ ] T501 ES+nori 이미지: `docker/elasticsearch-nori/Dockerfile`(elasticsearch:8.x + analysis-nori) + docker-compose 서비스
- [ ] T502 `PropertyDocument`(@Document(indexName="property")) + `PropertyDocumentRepository`(ElasticsearchRepository)
- [ ] T503 인덱스 settings/mappings(korean_nori, decompound=mixed) JSON 리소스 + `PropertyIndexBootstrap`(없으면 생성)
- [ ] T504 [P] 통합: Testcontainers ElasticsearchContainer(nori) 기동 + 인덱스 생성 확인

## Phase 2. Worker 핸들러 레지스트리 리팩터 (4차 확장)
- [ ] T510 `OutboxEventHandler` 인터페이스(supports/handle) + `OutboxWorker.publishOne` 을 핸들러 위임으로 변경
- [ ] T511 `NotificationOutboxHandler`: 기존 NotificationDispatcher 를 핸들러로 감쌈(NotificationType supports)
- [ ] T512 [P] 회귀: 4차 알림 발행/멱등/DEAD 테스트 그대로 통과(라우팅 후에도 동작)

## Phase 3. 색인 이벤트 적재 + 핸들러
- [ ] T520 매물 승인/수정/비활성에서 `PROPERTY_INDEX`/`PROPERTY_UNINDEX` append(event_key 에 updatedAtMillis)
- [ ] T521 `PropertyIndexOutboxHandler`: INDEX→최신 매물 재조회 후 upsert(ACTIVE), UNINDEX→deleteById; 비ACTIVE 방어 삭제
- [ ] T522 [P] 통합: 승인→Outbox→핸들러 색인→ES 문서 존재; 수정→재색인(키 변경); 비활성→삭제

## Phase 4. 검색 서비스 + 엔드포인트
- [ ] T530 `PropertyEsSearchCondition` + `PropertySearchEsService`(NativeQuery: multi_match 부스팅 + filter, status=ACTIVE)
- [ ] T531 `GET /api/properties/search` 컨트롤러(q + 필터 + 페이지) → PropertySummaryResponse 매핑
- [ ] T532 [P] 통합: 관련도(부스팅 순위), 복합어 decompound("역세권"), 필터+q 조합, q 없을 때 최신순

## Phase 5. 마감
- [ ] T540 통합 E2E: 매물 승인→색인→검색 노출→수정 재색인→비활성 제외
- [ ] T541 [P] docs/api-design·tech-stack(ADR ES)·ROADMAP·project-status 5차, 인수기준 체크

## 의존성
```
Phase1(인프라/인덱스) → Phase2(Worker 라우팅) → Phase3(색인) → Phase4(검색) → Phase5
```
