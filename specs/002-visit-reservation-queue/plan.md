# Implementation Plan: 방문 예약 대기열 (2차)

- Branch prefix: `feat/002-p<phase>-*`
- Spec: `./spec.md` (§6 확정 결정 반영)
- Constitution: v1.0.0

## Summary
방문 슬롯(MySQL) + Redis Sorted Set 대기열 + TTL 예약권(원자적 Lua 발급) + Mock 결제로 슬롯당 1명 확정.
HELD 는 MySQL 에 두지 않고 Redis 토큰 존재로 파생(§6-2). Redis↔MySQL 동기화는 **예약 확정 1지점**뿐.

## Technical Context
- 추가 의존성: `spring-boot-starter-data-redis`
- 인프라: `docker-compose.yml` 에 `redis:7` 추가, `spring.data.redis.*` 설정
- Config: `reservation.token-ttl-seconds`(300), `reservation.sweep-interval-ms`(2000), `reservation.fee-amount`(mock 결제 금액)

## Constitution Check
| 원칙 | 준수 |
| --- | --- |
| II. 멱등/원자성 | 예약권 발급은 **단일 Lua**(EXISTS→ZPOPMIN→SET PX). 확정은 상태조건부 + `confirmed_slot_key` UNIQUE. 결제 확정 멱등 |
| V. 정합성 우선 | 확정만 MySQL 트랜잭션. Redis 는 큐/토큰(임시). 부분실패 지점 최소화 |
| VI. 차수 분리 | 환불/정산은 3차. Payment 는 READY/PAID/FAILED 까지 |
| VIII. 상태전이 테스트 | 발급 원자성·확정·만료 재발급·중복예약 방지 테스트 |

## Redis 설계
```
대기열   ZSET  waiting:visit-slot:{slotId}   member=userId, score=요청 timestamp(ms)
활성슬롯 SET   waiting:slots                 sweep 대상 slotId 집합
예약권   STR   reservation-token:{slotId}    value=userId, TTL=300s   ← 슬롯당 단일 키
```

### Lua `tryIssue(slotId)` — 원자적 발급 (KEYS: token, queue / ARGV: ttlMs)
```lua
if redis.call('EXISTS', KEYS[1]) == 1 then return false end   -- 이미 발급중
local popped = redis.call('ZPOPMIN', KEYS[2])                  -- 선두 pop
if #popped == 0 then return false end                          -- 큐 빔
redis.call('SET', KEYS[1], popped[1], 'PX', tonumber(ARGV[1]))
return popped[1]                                               -- 발급된 userId
```
- Worker 다중 인스턴스에서도 슬롯당 active token 1개 보장(§2.2).

## 핵심 흐름 (D-decisions)

### D1. 발급 트리거 (§6-1)
`tryIssue` 를 멱등 호출: ①대기열 진입 직후 ②순번 조회 시 ③**sweep 스케줄러(2초)** — TTL 만료 후 요청이 없어도 다음 대기자 발급(백스톱).
- sweep: `waiting:slots` 순회 → 각 slot `tryIssue` → 큐 비었고 토큰 없으면 set 에서 제거.

### D2. 예약 생성 (§6-3)
`POST /visit-slots/{slotId}/reservations` (USER):
1. `reservation-token:{slotId}` 값 == 요청 userId 검증(아니면 403)
2. slot 이 MySQL `OPEN` 인지 확인(아니면 409)
3. 트랜잭션: `Reservation(PENDING_PAYMENT)` + `Payment(READY, amount=fee)` 생성
- 토큰 보유자만 예약 가능 → 동시 다중 PENDING 방지(토큰 1개).

### D3. 결제 확정 (§2.4, §6-3)
`POST /payments/{paymentId}/confirmation` (USER):
1. 토큰(Redis) 소유자 일치 확인
2. **단일 MySQL 트랜잭션**: Payment `READY→PAID`, Reservation `→CONFIRMED`, `visit_slot OPEN→RESERVED`
   - 최종 방어: `reservation.confirmed_slot_key`(CONFIRMED 일 때 slotId, 아니면 NULL) UNIQUE
3. 커밋 후 Redis 토큰 삭제 + `waiting:visit-slot:{slotId}` 큐 삭제 + `waiting:slots` 에서 제거
- 멱등: 이미 CONFIRMED 면 그대로 반환. 경쟁 실패(UNIQUE/상태)는 409.

### D4. 만료/취소
- TTL 만료: 토큰 자동 소멸 → sweep 이 다음 대기자에게 발급. PENDING 예약은 `POST /payments/{id}/failure` 또는 sweep 연계로 `EXPIRED` 정리(2차: failure 로 명시 처리, 미결제 방치분은 조회 시 만료 반영).
- `POST /payments/{id}/failure`: Payment `FAILED`, Reservation `EXPIRED`, 토큰 삭제 → 다음 대기자.

## Architecture / Package
```
com.jipsanim.reservation
├─ slot        (VisitSlot, 등록/조회/삭제)
├─ queue       (WaitingQueueService: enqueue/rank/tryIssue(Lua)/hasToken/release, RedisConfig)
├─ domain      (Reservation, Payment + enums)
├─ service     (ReservationService: 생성/확정/실패, 확정 트랜잭션)
├─ scheduler   (TokenSweepScheduler: 2초 tryIssue 백스톱)
└─ controller  (slot/waiting/reservation/payment API)
```

## Testing Strategy
- 단위: Lua tryIssue(토큰 유무/빈 큐), 확정 상태전이/멱등
- 통합(Testcontainers MySQL + Redis): 예약권 1개 보장, 확정→RESERVED, 만료 후 재발급, 중복예약 방지(confirmed_slot_key)
- 부하(k6 `reservation-queue.js`): 동시 500명 진입 → 순번 정합·확정 1건·중복 0

## Phasing
1. Redis 인프라 + WaitingQueue(Lua) + 단위테스트
2. VisitSlot 엔티티/CRUD
3. 대기열 진입/순번 + tryIssue 트리거
4. 예약 생성(PENDING_PAYMENT + Payment READY)
5. 결제 확정 트랜잭션(+failure)
6. sweep 스케줄러
7. 통합 + k6 부하

## Complexity Tracking
- Redis↔MySQL 이중 저장: 확정 1지점으로 국한(§6-2)해 정당화. 그 외 상태(HELD)는 파생.
