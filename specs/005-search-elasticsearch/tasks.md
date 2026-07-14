# Tasks: Elasticsearch nori 한글 검색 (5차)

규칙: `[P]` 병렬 가능. 색인/검색은 테스트 먼저 가능한 것부터. 브랜치 `feat/005-p<phase>-*`.

## Phase 1. ES 인프라 + 인덱스 매핑
- [ ] T500 `spring-boot-starter-data-elasticsearch` 의존성 + `spring.elasticsearch.uris` + **`search.elasticsearch.enabled`(운영 true, 공통 테스트 false, 리뷰 P1)**
- [ ] T501 ES+nori 이미지: `docker/elasticsearch-nori/Dockerfile`(**버전 pin** 예 8.13.4 + analysis-nori) + docker-compose(discovery.type=single-node, xpack.security.enabled=false, ES_JAVA_OPTS heap) — 리뷰 P2
- [ ] T502 `PropertyDocument`(**@Document(createIndex=false)**, **propertyId long(정렬) + primaryImageUrl keyword**, 리뷰 P1) + `PropertyDocumentRepository`
  - `@EnableElasticsearchRepositories` 는 별도 `ElasticsearchRepositoryConfig`(**@ConditionalOnProperty(search.elasticsearch.enabled)**), 공통 테스트 yml `spring.data.elasticsearch.repositories.enabled=false` (ES 빈 미생성, 리뷰 P1)
- [ ] T503 인덱스 settings/mappings JSON: **named filter `korean_pos_filter`(nori_part_of_speech)** + analyzer `korean_nori`(decompound=mixed) 참조(리뷰 P2) + `PropertyIndexBootstrap`(ApplicationRunner, **@ConditionalOnProperty(search.elasticsearch.enabled)**, 없으면 생성)
- [ ] T504 [P] 통합: Testcontainers ElasticsearchContainer(nori) 기동 + 인덱스 생성 확인

## Phase 2. Worker 핸들러 레지스트리 리팩터 (4차 확장)
- [ ] T510 `OutboxEventHandler` 인터페이스(supports/handle) + `OutboxWorker.publishOne` 을 핸들러 위임으로 변경
- [ ] T511 `NotificationOutboxHandler`: 기존 NotificationDispatcher 를 핸들러로 감쌈(NotificationType supports)
- [ ] T512 [P] 회귀: 4차 알림 발행/멱등/DEAD 테스트 그대로 통과(라우팅 후에도 동작)

## Phase 3. 색인 이벤트 적재 + 핸들러
- [ ] T520 `PropertyIndexEventRecorder`(**enabled=true 일 때만 append, 아니면 no-op** — 고아 이벤트/DEAD 방지, 리뷰 P1) + 도메인 연결: **전이 판정** `prev!=ACTIVE&&new==ACTIVE`→`PROPERTY_INDEX:{id}:{uuid}`, `prev==ACTIVE&&new!=ACTIVE`→`PROPERTY_UNINDEX:{id}:{uuid}`(UUID generation). 승인은 **기존 `PROPERTY_APPROVED` 알림 유지 + 추가 append**
- [ ] T521 `PropertyIndexOutboxHandler`(@ConditionalOnProperty): INDEX→최신 매물 재조회 후 upsert(ACTIVE, propertyId·primaryImageUrl 포함), UNINDEX→deleteById; 비ACTIVE 방어 삭제
- [ ] T522 [P] **Recorder 분기 테스트(ES 불필요, 빠름, 리뷰 P2)**: `enabled=true`+`worker-enabled=false` 에서 승인→`PROPERTY_INDEX` append / ACTIVE softDelete→`PROPERTY_UNINDEX` append 확인; `enabled=false` 면 append 없음(no-op)
- [ ] T523 [P] 통합(ES): 승인→Outbox→핸들러 색인→ES 문서 존재; ACTIVE softDelete→삭제 (반복 전이 E2E 는 hide/unhide 도입 시로 이연)

## Phase 4. 검색 서비스 + 엔드포인트
- [ ] T530 `PropertyEsSearchCondition` + `PropertySearchEsService`(NativeQuery: multi_match 부스팅 + filter, status=ACTIVE, **track_total_hits=true**)
- [ ] T531 `GET /api/properties/search` 컨트롤러(@ConditionalOnProperty, q + 필터 + 페이지) → PropertySummaryResponse 매핑. **검증(리뷰 P2)**: `minDeposit<=maxDeposit`·`minRent<=maxRent`·`minArea<=maxArea`·`page>=0`·`1<=size<=100`·`(page+1)*size>10000`→400. 정렬 tie-breaker(propertyId desc). **SecurityConfig permitAll 에 `/api/properties/search` 명시(리뷰 P3)**
- [ ] T532 `ErrorCode.SEARCH_UNAVAILABLE`(503) 추가 + ES 예외 핸들링(GlobalExceptionHandler) — 리뷰 P2
- [ ] T533 [P] 통합: 관련도(부스팅 순위), 복합어 decompound("역세권"), 필터+q 조합, q 없을 때 최신순

## Phase 5. 마감
- [ ] T540 통합 E2E: 매물 승인→색인→검색 노출→비활성→삭제→검색 제외
- [ ] T541 [P] docs/api-design·tech-stack(ADR ES)·ROADMAP·project-status 5차, 인수기준 체크

## 의존성
```
Phase1(인프라/인덱스) → Phase2(Worker 라우팅) → Phase3(색인) → Phase4(검색) → Phase5
```
