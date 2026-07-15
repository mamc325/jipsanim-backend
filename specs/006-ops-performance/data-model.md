# Data Model: 인기 매물 조회수·캐싱 + 관측성 (6차)

DDL 은 `property.view_count` 1개 컬럼 추가뿐. 나머지 상태는 Redis 키(휘발/근사) + Prometheus 메트릭.

## 1. MySQL 변경

### property (컬럼 추가 — 테이블명 `property`, `@Table(name="property")`)
| 컬럼 | 타입 | 제약/기본 | 설명 |
| --- | --- | --- | --- |
| view_count | BIGINT | NOT NULL DEFAULT 0 | 생애 누적 조회수(writeback 반영). 랭킹 트렌딩 score 와 분리 |

```sql
ALTER TABLE property ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;
```
- Property 엔티티에 `long viewCount` 필드 추가. writeback 은 `UPDATE ... SET view_count = view_count + :delta`(JPQL bulk 또는 native, 영속성 컨텍스트 flush 주의).
- **DTO(P2)**: 인기 목록은 **신규 `PopularPropertyResponse`**(요약 필드 + `viewCount`). 상세는 `PropertyDetailResponse` 에 `viewCount` 추가. 기존 `PropertySummaryResponse`(QueryDSL 목록·5차 ES 검색 공유)는 **불변**.

## 2. Redis 키 스키마

| 키 | 타입 | TTL | 용도 | 원자성 |
| --- | --- | --- | --- | --- |
| `view:dedup:{propertyId}:{viewerKey}` | string | window(기본 30m) | 중복조회 방지 | 카운트 Lua 내 `SET NX EX` |
| `view:pending` | hash(field=propertyId, val=delta) | 없음 | writeback 대기 델타 | 카운트 Lua 내 `HINCRBY` |
| `view:flushing` | hash | 없음(일시, 고정 키) | 배출 중 스냅샷 | `EXISTS`→`RENAME`(원자 배출) |
| `property:popular` | zset(member=propertyId, score=트렌딩) | 없음 | 인기 랭킹 | 카운트 Lua 내 `ZINCRBY` / `ZUNIONSTORE WEIGHTS`(감쇠) |
| `popular:list` | string(json, Top-50 단일 키) | 60s | 인기목록 cache-aside(앱에서 limit slice) | `SET EX`, 무효화 `DEL` 1회 |
| `property:detail:{id}` | string(json, **ACTIVE 만**) | 300s | 상세 cache-aside | `SET EX` |

- `viewerKey`: 인증 `u:{userId}`, 비인증 `ip:{clientIp}`.
- 모든 TTL/윈도우/감쇠 factor·epsilon·over-fetch 는 프로퍼티(`viewcount.*`, `popular.*`, `cache.*`).

### 조회수 카운트 (원자 Lua, P1 — dedup+델타+랭킹 부분성공 방지)
```
-- KEYS[1]=view:dedup:{id}:{viewerKey}, KEYS[2]=view:pending, KEYS[3]=property:popular
-- ARGV[1]=window(s), ARGV[2]=propertyId
if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
  redis.call('HINCRBY', KEYS[2], ARGV[2], 1)
  redis.call('ZINCRBY', KEYS[3], 1, ARGV[2])
  return 1   -- counted
else
  return 0   -- dedup 중복 → view.dedup.skip
end
```
- **ACTIVE 공개 상세 성공 응답에만**, **cache hit/miss 무관**하게 호출(P2). best-effort(Redis 예외 삼킴).

### 인기목록 조립 (over-fetch → ACTIVE 필터 → 순서 복원, P4·P5)
```
ids = ZREVRANGE property:popular 0 (OVERFETCH-1)     # 기본 200, stale/inactive 섞임 대비
rows = SELECT ... FROM property WHERE id IN (ids) AND status='ACTIVE'   # 순서 미보장
byId = map(propertyId -> PopularPropertyResponse)
ordered = ids 순서대로 byId 재정렬 후 상위 MAX=50           # Redis 트렌딩 순서 복원
SET popular:list json(ordered) EX 60
```
- **부족 시(P2)**: over-fetch(200) 내 ACTIVE 가 50 미만이면 나오는 만큼만 반환(더 깊은 재조회 없음).
- **DB ACTIVE 필터 = 이탈 제외 권위(P1)**: ZSET 에 stale member(ZREM 실패/재유입)가 있어도 이 필터로 응답에서 제외. cache hit 은 재필터 안 하므로 evict 실패 시 최대 60s stale.

### writeback 원자 배출 절차 (RENAME 은 source 부재 시 에러 → EXISTS 선확인)
```
if EXISTS view:flushing:  goto PROCESS           # 직전 주기 실패 잔존분 우선 처리
elif EXISTS view:pending:  RENAME view:pending view:flushing   # 원자, 이후 유입은 새 view:pending 로
else:                      return                # 처리할 것 없음(no-op)
PROCESS:
  HGETALL view:flushing                          # {propertyId: delta}
  BEGIN; UPDATE property SET view_count = view_count + :delta WHERE id=:id ...; COMMIT
  DEL view:flushing                              # 커밋 성공 후에만
```
- 유실 0: 배출 순간 이후 증가분은 새 pending. 크래시 창(커밋~DEL)은 중복 가산 가능 → 근사 허용.

### 일 감쇠(트렌딩)
```
ZUNIONSTORE property:popular 1 property:popular WEIGHTS 0.5   # 전체 score × factor (원자)
ZREMRANGEBYSCORE property:popular -inf (1.0)                  # 임계 미만 제거(집합 유한)
```
- factor(기본 0.5)·epsilon(기본 1.0) 설정. Lua 는 fallback 구현.

## 3. Prometheus 메트릭 (Micrometer)

**코드명은 dot 표기, Prometheus 노출명은 Micrometer 가 Counter 에 `_total` 을 자동 부착(P6)** — 코드에 직접 `_total` 을 붙이지 않는다.

| 코드명(Micrometer) | Prometheus 노출명 | 타입 | 태그 | 의미 |
| --- | --- | --- | --- | --- |
| `cache.requests` | `cache_requests_total` | Counter | `cache`(popular/detail), `result`(hit/miss) | 캐시 적중/미스 |
| `cache.errors` | `cache_errors_total` | Counter | `cache` | Redis 예외로 degrade(DB 우회) |
| `view.dedup.skip` | `view_dedup_skip_total` | Counter | - | 중복조회로 미가산 |
| `view.flush` | `view_flush_total` | Counter | - | writeback 배치 실행 수 |
| `view.flush.delta` | `view_flush_delta_total` | Counter | - | writeback 반영 델타 합 |
| (기본) | `http_server_requests_*` | Timer | uri, status | 엔드포인트 레이턴시(Actuator 기본) |
| 상세/인기/검색 커스텀 Timer | `*_seconds*` | Timer | endpoint(**고정값 `property_detail`/`popular`/`search` 만**, P4) | 주요 조회 경로 지연 |

- **`/actuator/prometheus` 스크레이프(구현)**: 앱 포트(8080) 동일, `management.prometheus.metrics.export.enabled: true` + PUBLIC_PATHS permitAll(무인증). 관리 포트 분리는 자식 컨텍스트 보안 미적용으로 미채택. 외부 노출은 compose 미공개/프록시 차단. 대시보드: 캐시 hit-ratio, dedup 비율, writeback 델타, 엔드포인트 p95.

## 4. 상세 캐시 읽기·stale 정책 (P1·P2)
- **역할별 읽기**(비공개 표현 도달 가능성 기준):
  | 역할 | 읽기 | viewerKey(dedup) |
  | --- | --- | --- |
  | anonymous | cache-first | `ip:{clientIp}`(trust-proxy 시 XFF) |
  | USER | cache-first | `u:{userId}` |
  | REALTOR | 캐시 read/write 우회 → DB 직조회 | `u:{userId}` |
  | ADMIN | 캐시 read/write 우회 → DB 직조회 | `u:{userId}` |
- 캐시 **저장은 `countablePublicAccess=true`(ACTIVE 공개표현) + anonymous/USER miss 경로만**. 비공개 표현·404 저장 금지. 카운트는 결과가 ACTIVE 면 모든 역할(dedup).
- **stale 정책**: ACTIVE 이탈 evict 는 afterCommit best-effort → 실패 시 **anonymous/USER public cache hit** 만 최대 TTL(300s) stale. REALTOR/ADMIN 은 DB 직조회라 무관. **stale hit 시 조회수/랭킹 증가도 근사 오차로 허용**.

## 5. 상태/정합성 요약
- `view_count`(DB, 영속·근사 누적) vs `property:popular` score(Redis, 휘발·감쇠 트렌딩) — **의도적 분리**.
- ACTIVE 이탈 시(삭제/반려) `ZREM property:popular` + `DEL property:detail:{id}` + `DEL popular:list`(단일 키 1회)(afterCommit best-effort).
- ACTIVE 진입 시(승인) 상세 캐시 evict(다음 조회에 최신 반영).
- **인기목록 이탈 제외 = 2계층(P1)**: ① miss 조립 시 **DB `WHERE status='ACTIVE'` 필터가 권위**(ZSET stale member 있어도 응답 제외). ② `popular:list` cache hit 은 evict 실패 시 **최대 60s stale 허용**. ③ ZSET stale member(ZREM 실패 / stale detail hit 의 `ZINCRBY` 재유입)는 **일 감쇠 + DB 필터로 자연 정리** — ZSET 자체는 "제외 보장" 대상 아님.
- Redis 유실: 조회수 pending 손실분만 근사 오차, `view_count` 는 DB 영속. 랭킹은 콜드 스타트 자가 회복.
