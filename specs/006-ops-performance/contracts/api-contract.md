# API Contract: 인기 매물 조회수·캐싱 (6차)

- Base `/api`, 공통 응답 래퍼 1차와 동일.
- 6차는 **인기 매물 엔드포인트 1개 추가** + 기존 `GET /api/properties/{id}` 응답에 `viewCount` 필드 추가(호환). 조회수 카운팅/캐싱/랭킹은 부수효과(스펙 무변경).
- 관측성 `/actuator/prometheus` 는 운영/모니터링 엔드포인트(도메인 API 아님).

---

## 인기 매물 (사용자)

### GET /api/properties/popular  [PUBLIC]
트렌딩 랭킹 상위 매물. Redis Sorted Set(일 감쇠) + cache-aside(TTL 60s). ACTIVE 매물만.

**query params**
| param | 기본 | 의미 |
| --- | --- | --- |
| limit | 10 | 상위 개수. 1~50 (초과 시 400) |

```json
// res 200 (List<PopularPropertyResponse> — 신규 DTO, 요약 필드 + viewCount. PropertySummaryResponse 불변, P2)
{
  "success": true,
  "data": [
    { "propertyId": 12, "title": "강남역 5분 풀옵션 오피스텔", "regionName": "강남구",
      "dealType": "MONTHLY_RENT", "deposit": 10000000, "monthlyRent": 700000,
      "area": 33.0, "roomCount": 1, "primaryImageUrl": "https://...", "viewCount": 1532 }
  ],
  "error": null
}
```
- **응답 DTO 는 신규 `PopularPropertyResponse`**(요약 필드 + `viewCount`). 기존 `PropertySummaryResponse`(QueryDSL 목록·5차 ES 검색 공유)는 건드리지 않는다(P2).
- **조립 순서(P4·P5)**: miss 시 `ZREVRANGE property:popular 0 OVERFETCH-1`(over-fetch, 기본 200) → DB `WHERE id IN(...) AND status='ACTIVE'` → **`propertyId→PopularPropertyResponse` 맵으로 ZREVRANGE 순서(트렌딩 desc) 복원**(DB IN 은 순서 미보장) → 상위 MAX=50 캐시 → `limit` slice. 동점 순서는 Redis member 순.
- **부족 시(P2)**: over-fetch 내 ACTIVE 가 요청 `limit`(최대 50)에 못 미치면 **나오는 만큼만 반환**(더 깊은 재조회 없음 — 트렌딩 근사).
- `viewCount` 는 생애 누적(DB, writeback 반영·근사). 트렌딩 score 자체는 응답에 노출하지 않음(내부 신호).
- **cache-aside(단일 키, P7)**: `popular:list`(Top-`MAX`=50) hit 시 캐시에서 slice 반환, miss 시 조립 후 캐시(TTL 60s). 무효화는 `DEL popular:list` 1회. hit/miss 는 `cache.requests{cache="popular"}`(노출명 `cache_requests_total`) 로 계측.
- **ACTIVE 이탈 제외 = 2계층(P1)**: ① **miss 조립 시 DB `WHERE status='ACTIVE'` 필터가 제외를 보장(권위)** — ZSET 에 stale member 가 있어도 응답 미포함. ② **cache hit 은 재필터 안 함** → `DEL popular:list`(best-effort) 실패 시 **최대 60s stale**(비활성 매물이 목록에 잔존 가능, 허용 정책). ③ ZSET stale member(ZREM 실패 또는 stale detail hit 의 ZINCRBY 재유입)는 **감쇠/DB 필터로 자연 정리** — "랭킹 제외"를 ZSET 기준으로 보장하지 않음.
- **degrade**: Redis 장애 시 캐시/랭킹 우회 → 빈 목록 대신 DB `view_count` desc 폴백 조회(핵심 조회 응답 보장, 원칙 V). 폴백은 `cache.errors++`.

---

## 매물 상세 (기존, 필드 추가)

### GET /api/properties/{id}  [PUBLIC, 기존]
- `PropertyDetailResponse` 에 **`viewCount`(long)** 추가(검색과 비공유 DTO라 안전, P2). 그 외 기존 스펙 동일.
- **부수효과(조회수)**: 서비스가 **ACTIVE 공개표현을 반환할 때만**(`getDetail`→`PropertyDetailResult.countablePublicAccess=true`, P1 — 현 코드는 ACTIVE 면 소유자/ADMIN 판별 전 즉시 반환이라 구분 위해 결과 타입 필요), **cache hit/miss 와 무관하게**(P2) `ViewCountService.record(id, viewerKey)` — **단일 Lua**(`SET NX EX`+`HINCRBY view:pending`+`ZINCRBY property:popular`). 비공개 표현·404 미집계. **best-effort**: Redis 장애가 상세 응답에 영향 없음. GET 안전성 유지(카운트는 부가 계측).
- **cache-aside(ACTIVE 공개표현만, P4·P1)**: `property:detail:{id}`(TTL 300s) 는 `countablePublicAccess=true` 일 때만 저장/조회(카운트와 동일 게이트). 비공개 표현·404 는 캐시 저장 금지.
  - **읽기 정책(P1, 역할별)**: anonymous=cache-first(viewerKey `ip`), **USER=cache-first**(viewerKey `u:{userId}`), **REALTOR/ADMIN=캐시 read/write 우회 → DB 직조회**. 소유자/ADMIN 은 stale 을 받지 않음.
  - **stale 정책(P2)**: 상태 전이 시 evict 는 afterCommit **best-effort** → **evict 실패 시 anonymous·USER public cache hit 에 한해 최대 300초 stale 허용**(REALTOR/ADMIN 무관). stale cache hit 시 **조회수/랭킹 증가도 근사 오차로 허용**. `viewCount` 는 캐시 스냅샷(근사).

---

## 내부/부수효과 (API 아님)
- **조회수 카운트**: 단일 Lua(`SET NX EX`+`HINCRBY view:pending`+`ZINCRBY property:popular`, P1) — ACTIVE 공개 상세 성공 시, cache hit/miss 무관.
- **writeback 스케줄러**: `EXISTS`→`RENAME view:pending view:flushing` 원자 배출 → DB `property.view_count += delta`(주기 기본 60s).
- **일 감쇠 스케줄러**: `ZUNIONSTORE property:popular 1 property:popular WEIGHTS factor`(기본 0.5) + `ZREMRANGEBYSCORE -inf (epsilon)`(기본 04:00).
- **ACTIVE 이탈 정리(afterCommit best-effort)**: 삭제/반려 커밋 후 `ZREM property:popular {id}` + `DEL property:detail:{id}` + `DEL popular:list`(단일 키).

## 관측성 (운영)
- `GET :9090/actuator/prometheus` — Micrometer 메트릭 스크레이프. **`management.server.port=9090` 분리 + compose 9090 호스트 미매핑(내부 전용)**(P3·P5). **SecurityConfig 에 actuator 전용 permit 명시**(`EndpointRequest.to("prometheus","health")` permitAll) — `anyRequest().authenticated()` 로는 401 위험. `:9090/actuator/health` 는 healthcheck 용.

## 에러 코드
| code | HTTP | 의미 |
| --- | --- | --- |
| VALIDATION_ERROR | 400 | `limit` 범위(1~50) 위반 |

> 6차는 도메인 계약을 깨지 않는다 — 인기 엔드포인트 추가 + 상세에 `viewCount` 필드 추가(하위호환) + 부수효과(카운트/캐시/랭킹).
