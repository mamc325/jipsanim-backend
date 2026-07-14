# Feature Spec: Elasticsearch nori 한글 검색 (5차)

- Feature Branch prefix: `feat/005-p<phase>-*`
- Status: Design Locked (구현 대기)
- Constitution: v1.0.0 (원칙 VII 검색 품질 우선, IV Outbox, VI 차수 분리)
- 선행: 1차(매물/QueryDSL 검색)·4차(Outbox) 완료

## 1. 범위
- **nori 형태소 기반 한글 전문검색 + 관련도 랭킹**을 신규 엔드포인트로 제공
- MySQL → ES 색인은 **4차 Outbox 재사용**(매물 **승인(ACTIVE) 시 색인, 비활성(ACTIVE 이탈) 시 삭제**)
- 근거는 **latency 가 아니라 검색 품질/관련도**(원칙 VII)
- Out of scope: 자동완성/오타교정/개인화 랭킹, ES 다중 노드, **ACTIVE 매물 수정 재색인**(현 도메인 정책상 DRAFT/PENDING 만 수정 가능 → 재검증·재승인 정책과 함께 후속 차수, 리뷰 P1)

## 2. 확정 결정 (2026-07-14)

### 2-1. 색인 동기화 → **Outbox 기반 색인 (4차 재사용)**
- **ACTIVE 진입/이탈 기준(리뷰 P1)**: `prev != ACTIVE && new == ACTIVE` → `PROPERTY_INDEX`, `prev == ACTIVE && new != ACTIVE` → `PROPERTY_UNINDEX`. (enum 목록 나열 대신 전이 기준 → HIDDEN 도 자동 안전, `PENDING→REJECTED` 처럼 색인된 적 없는 전이엔 UNINDEX 안 붙음.)
- 승인 시 **기존 `PROPERTY_APPROVED` 알림 이벤트는 유지**하고 `PROPERTY_INDEX` **를 추가 append**(4차 알림 대체 아님, 리뷰 P1).
- 적재는 도메인 트랜잭션과 동일 커밋. Worker 가 폴링 발행 시 ES 색인/삭제. 정합성은 4차 Outbox 재사용.

### 2-2. 검색 아키텍처 → **ES 전담 신규 엔드포인트(텍스트 + 필터)**
- `GET /api/properties/search?q=&필터...` — nori 전문검색 + 구조적 필터 + 관련도 정렬을 **ES 가 전담**.
- 기존 `GET /api/properties`(QueryDSL 조건 검색)는 **유지**(호환). 5차는 검색 품질 경로를 추가.

### 2-3. 클라이언트 → **Spring Data Elasticsearch**
- `@Document(indexName="property", createIndex = false)` + `NativeQuery`(복합 multi_match/필터).
- **자동 인덱스 생성 차단(리뷰 P1)**: Spring Data 가 기본(nori 미적용) 매핑으로 인덱스를 먼저 만들지 않도록 `createIndex=false`. settings/mapping 생성은 `PropertyIndexBootstrap` 이 JSON 리소스로 담당.

### 2-4. 분석/랭킹 → **nori decompound=mixed + 필드 부스팅**
- analyzer: `nori`(`decompound_mode=mixed` — 복합어 원형+분해 동시 색인 → "역세권"·"풀옵션" 검색 품질).
- 질의: `multi_match`(title^3, nearStation^2, regionName^2, description^1) → 형태소 매칭 + 필드 가중 관련도.
- 동의어 사전은 **후순위**(도입 시 synonym filter + 색인 재구성).

## 3. 파생 설계 (결정에서 도출)
- **Worker 라우팅 일반화**: 4차 Worker 는 `NotificationDispatcher` 단일 처리 → **이벤트 유형별 핸들러 레지스트리**로 리팩터(알림 vs ES 색인). `OutboxEventHandler.supports(eventType)` + `handle(event)`.
- **색인 이벤트 키(전이당 generation, 리뷰 P1)**: `PROPERTY_INDEX:{propertyId}:{gen}` / `PROPERTY_UNINDEX:{propertyId}:{gen}`. `{gen}`=append 시점 UUID. 상태값만 쓰면 `event_key` UNIQUE 때문에 **반복 전이**(ACTIVE↔HIDDEN)의 두 번째 UNINDEX 가 no-op 유실 → 전이마다 새 이벤트가 되도록 UUID 부착. 색인 이벤트는 producer dedup 대상 아님(각 append 유일)이나 **ES upsert/delete(id=propertyId) 가 멱등**이라 최종 1건.
- **`search.elasticsearch.enabled` 스위치(리뷰 P1)**: Bootstrap/색인 핸들러/검색 빈을 `@ConditionalOnProperty` 로 게이팅(일반 테스트=false → ES 없이 기동, ES 통합 테스트=true).
  - **Producer 도 게이팅**: 색인 이벤트 append 는 `PropertyIndexEventRecorder` 를 통해 **enabled=true 일 때만** 수행(disabled 면 no-op). → enabled=false 인데 색인 이벤트만 쌓여 handler 없음으로 **DEAD 누적되는 문제 원천 차단**(리뷰 P1). 알림 등 다른 Outbox 흐름은 영향 없음.
- **인프라(원칙 VI, 5차 신규)**: Testcontainers `ElasticsearchContainer` — **nori 플러그인 포함 커스텀 이미지**(버전 pin). 로컬은 docker-compose 에 ES 추가.

## 4. 색인 대상/시점
| 전이 | 이벤트 | ES 동작 |
| --- | --- | --- |
| `prev != ACTIVE && new == ACTIVE`(승인) | PROPERTY_INDEX | 문서 upsert |
| `prev == ACTIVE && new != ACTIVE`(비활성, HIDDEN 포함) | PROPERTY_UNINDEX | 문서 삭제 |
- ACTIVE 진입/이탈 **전이 기준**으로 판정 → `PENDING→REJECTED`(색인된 적 없음)엔 이벤트 없음, enum 목록 나열 회피(리뷰 P1).
- 승인 시 기존 `PROPERTY_APPROVED` 알림 이벤트 **유지** + `PROPERTY_INDEX` 추가.
- (ACTIVE 매물 수정 재색인은 범위 밖 — 현 정책상 DRAFT/PENDING 만 수정 가능)

## 5. 엔티티/인덱스 (상세 data-model)
- **PropertyDocument**(ES `property` 인덱스): id(`_id`=propertyId 문자열), **propertyId(long, 정렬용)**, title, description, roadAddress, regionName, nearStation(nori text), sigunguCode/dealType/propertyType/status/**primaryImageUrl**(keyword), deposit/monthlyRent/area/roomCount(numeric), realtorId, createdAt. (`primaryImageUrl` 목록 DTO 재사용, `propertyId` 숫자 정렬 — 리뷰 P1)
- MySQL 스키마 변경 없음. OutboxEvent 는 4차 것 재사용(event_type 확장).

## 6. API (상세 contracts)
- `GET /api/properties/search?q=&sigunguCode=&dealType=&propertyType=&minDeposit=&maxDeposit=&minRent=&maxRent=&minArea=&maxArea=&roomCount=&page=&size=` [PUBLIC]
  - `q` 전문검색(nori) + 필터(term/range) + 관련도 정렬. `q` 없으면 필터+최신순.

## 7. 정합성/멱등 (구현 전 확정)
- 색인 적재는 도메인 커밋과 **동일 트랜잭션**(원칙 IV) → 유실 0. Outbox 이중 멱등 재사용.
- 색인 upsert 는 문서 id=propertyId 로 **멱등**(중복 이벤트 처리돼도 최신 문서 1건).
- ES 장애 시: 색인 이벤트는 재시도/DEAD 로 보존(4차 백오프). 검색 API 는 ES 의존(장애 시 5xx).

## 8. 인수 기준
- [x] 매물 승인 → OutboxEvent(PROPERTY_INDEX) 동일 커밋 적재 → Worker 색인 → 검색 노출. (`PropertyIndexingIntegrationTest`, E2E `PropertySearchE2ETest`)
- [x] **ACTIVE 매물 삭제(softDelete: ACTIVE→DELETED)** → PROPERTY_UNINDEX → 검색에서 제외. (현재 도메인에서 도달 가능한 유일한 ACTIVE 이탈)
- (전이 판정 규칙 `prev==ACTIVE && new!=ACTIVE` 은 일반형으로 유지 — hide/unhide 등 전이가 도메인에 추가되면 자동 적용. **반복 전이(ACTIVE↔HIDDEN) E2E 검증은 해당 전이 도입 시**로 이연, 리뷰 P1)
- [x] **품질**: "강남역 오피스텔" 검색 시 nori 형태소 매칭 + 필드 부스팅으로 관련 매물 상위 노출. (`PropertySearchApiIntegrationTest.relevance`)
- [x] **복합어 decompound**: 부분어 질의가 복합어를 매칭(`전력`→`한국전력공사`). (`PropertySearchApiIntegrationTest.decompound`)
  - ⚠️ 발견: 설계 예시 `역세권`은 이 nori config(`korean_pos_filter` stoptags 에 XSN 포함)에서 접미사 "권"이 제거되고 복합어 원형이 보존되지 않아(`역세권`→`[역세]`) 검색어로는 0건. `역세`로는 매칭됨. decompound 시연은 `전력→한국전력공사`로 대체(견고). 향후 개선: 사용자 사전(user_dictionary)에 `역세권` 등록 또는 stoptags 에서 XSN 제외 검토.
- [x] 필터(거래유형/가격/면적/방수) + q 조합 검색. (`PropertySearchApiIntegrationTest.filterCombo`)
- [x] 색인/검색이 기존 `GET /api/properties`(QueryDSL) 경로에 영향 없음. (별도 컨트롤러/엔드포인트, 기존 테스트 그린)

## 9. 다음 산출물
`plan` → `data-model` → `contracts` → `tasks`. (신규 인프라 = ES + nori)
