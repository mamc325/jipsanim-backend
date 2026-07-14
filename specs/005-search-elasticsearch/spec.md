# Feature Spec: Elasticsearch nori 한글 검색 (5차)

- Feature Branch prefix: `feat/005-p<phase>-*`
- Status: Design Locked (구현 대기)
- Constitution: v1.0.0 (원칙 VII 검색 품질 우선, IV Outbox, VI 차수 분리)
- 선행: 1차(매물/QueryDSL 검색)·4차(Outbox) 완료

## 1. 범위
- **nori 형태소 기반 한글 전문검색 + 관련도 랭킹**을 신규 엔드포인트로 제공
- MySQL → ES 색인은 **4차 Outbox 재사용**(매물 승인/수정/비활성 시 이벤트 적재 → Worker 가 ES 색인)
- 근거는 **latency 가 아니라 검색 품질/관련도**(원칙 VII)
- Out of scope: 자동완성/오타교정/개인화 랭킹, ES 클러스터 다중 노드 운영

## 2. 확정 결정 (2026-07-14)

### 2-1. 색인 동기화 → **Outbox 기반 색인 (4차 재사용)**
- 매물 승인/수정 → `OutboxEvent(PROPERTY_INDEX)`, 비활성화 → `OutboxEvent(PROPERTY_UNINDEX)` 적재(도메인 트랜잭션과 동일 커밋).
- Worker 가 폴링 발행 시 ES 색인/삭제 수행. 정합성(유실/중복 방지)은 4차 Outbox 보장 재사용.

### 2-2. 검색 아키텍처 → **ES 전담 신규 엔드포인트(텍스트 + 필터)**
- `GET /api/properties/search?q=&필터...` — nori 전문검색 + 구조적 필터 + 관련도 정렬을 **ES 가 전담**.
- 기존 `GET /api/properties`(QueryDSL 조건 검색)는 **유지**(호환). 5차는 검색 품질 경로를 추가.

### 2-3. 클라이언트 → **Spring Data Elasticsearch**
- `@Document` 매핑 + `NativeQuery`(복합 multi_match/필터). 인덱스 매핑/설정은 JSON 리소스로 관리.

### 2-4. 분석/랭킹 → **nori decompound=mixed + 필드 부스팅**
- analyzer: `nori`(`decompound_mode=mixed` — 복합어 원형+분해 동시 색인 → "역세권"·"풀옵션" 검색 품질).
- 질의: `multi_match`(title^3, nearStation^2, regionName^2, description^1) → 형태소 매칭 + 필드 가중 관련도.
- 동의어 사전은 **후순위**(도입 시 synonym filter + 색인 재구성).

## 3. 파생 설계 (결정에서 도출)
- **Worker 라우팅 일반화**: 4차 Worker 는 `NotificationDispatcher` 단일 처리 → **이벤트 유형별 핸들러 레지스트리**로 리팩터(알림 vs ES 색인). `OutboxEventHandler.supports(eventType)` + `handle(event)`.
- **색인 이벤트 멱등 키**: `PROPERTY_INDEX:{propertyId}:{updatedAtMillis}` — 매 수정마다 새 이벤트(단일 `:{propertyId}` 로 dedup 하면 후속 수정이 색인 안 됨). 핸들러는 현재 매물을 재조회해 **전체 문서 upsert**(색인 자체가 멱등).
- **인프라(원칙 VI, 5차 신규)**: Testcontainers `ElasticsearchContainer` — **nori 플러그인 포함 커스텀 이미지**. 로컬은 docker-compose 에 ES 추가.

## 4. 색인 대상/시점
| 트리거 | 이벤트 | ES 동작 |
| --- | --- | --- |
| 매물 승인(ACTIVE 전이) | PROPERTY_INDEX | 문서 upsert |
| 매물 수정(ACTIVE 상태) | PROPERTY_INDEX | 문서 upsert(재색인) |
| 매물 비활성(ACTIVE→그 외) | PROPERTY_UNINDEX | 문서 삭제 |
- 검색은 ACTIVE 매물만 노출 → 색인은 ACTIVE 진입 시, 삭제는 이탈 시.

## 5. 엔티티/인덱스 (상세 data-model)
- **PropertyDocument**(ES `property` 인덱스): id, title, description, roadAddress, regionName, nearStation(nori text), sigunguCode/dealType/propertyType/status(keyword), deposit/rent/area/roomCount(numeric), realtorId, createdAt.
- MySQL 스키마 변경 없음. OutboxEvent 는 4차 것 재사용(event_type 확장).

## 6. API (상세 contracts)
- `GET /api/properties/search?q=&sigunguCode=&dealType=&propertyType=&minDeposit=&maxDeposit=&minRent=&maxRent=&minArea=&maxArea=&roomCount=&page=&size=` [PUBLIC]
  - `q` 전문검색(nori) + 필터(term/range) + 관련도 정렬. `q` 없으면 필터+최신순.

## 7. 정합성/멱등 (구현 전 확정)
- 색인 적재는 도메인 커밋과 **동일 트랜잭션**(원칙 IV) → 유실 0. Outbox 이중 멱등 재사용.
- 색인 upsert 는 문서 id=propertyId 로 **멱등**(중복 이벤트 처리돼도 최신 문서 1건).
- ES 장애 시: 색인 이벤트는 재시도/DEAD 로 보존(4차 백오프). 검색 API 는 ES 의존(장애 시 5xx).

## 8. 인수 기준
- [ ] 매물 승인 → OutboxEvent(PROPERTY_INDEX) 동일 커밋 적재 → Worker 색인 → 검색 노출.
- [ ] 매물 수정 → 재색인(updatedAt 키로 새 이벤트) → 검색 결과 반영.
- [ ] 매물 비활성 → PROPERTY_UNINDEX → 검색에서 제외.
- [ ] **품질**: "강남역 오피스텔" 검색 시 nori 형태소 매칭 + 필드 부스팅으로 관련 매물 상위 노출.
- [ ] **복합어**: "역세권" 질의가 decompound 로 매칭.
- [ ] 필터(거래유형/가격/면적/방수) + q 조합 검색.
- [ ] 색인/검색이 기존 `GET /api/properties`(QueryDSL) 경로에 영향 없음.

## 9. 다음 산출물
`plan` → `data-model` → `contracts` → `tasks`. (신규 인프라 = ES + nori)
