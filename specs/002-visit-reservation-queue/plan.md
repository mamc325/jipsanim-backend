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

### D1. 발급 트리거 + 슬롯 상태 가드 (§6-1, P1-3)
Lua `tryIssue` 는 Redis 만 본다 → **호출 전 MySQL slot 상태 확인**을 감싼 `tryIssueIfSlotOpen(slotId)`:
```
slot = load(slotId)
if slot.status == OPEN → tryIssue(slotId)          // 발급
else                  → cleanupSlotRedis(slotId)   // 토큰/큐/active-set 삭제
```
멱등 호출 지점: ①대기열 진입 직후 ②순번 조회 시 ③**sweep 스케줄러(2초)**(TTL 만료 백스톱).
- sweep: `waiting:slots` 순회 → 각 slot `tryIssueIfSlotOpen` → 큐 비었고 토큰 없으면 set 제거.
- sweep 은 **만료 PENDING 정리도 수행**: `status=PENDING_PAYMENT AND expires_at < now` → `EXPIRED` + Payment `FAILED`(P1-2) → active_reservation_key NULL 화로 다음 진행 가능.
- **slot 상태 전이 시 `cleanupSlotRedis`**: RESERVED(확정)/CLOSED(마감)/EXPIRED → 토큰·큐·active-set 삭제.

### D2. 예약 생성 — 멱등 (§6-3, P1-1)
`POST /visit-slots/{slotId}/reservations` (USER):
1. `reservation-token:{slotId}` == userId 검증(아니면 403)
2. slot 이 MySQL `OPEN` 인지(아니면 409)
3. **멱등**: 해당 slot 에 이 사용자의 활성 `PENDING_PAYMENT` 예약이 있으면 **기존 예약/결제 반환**(신규 생성 금지)
4. 없으면 트랜잭션: `Reservation(PENDING_PAYMENT, expires_at=now+TTL)` + `Payment(READY, amount=fee)` 생성
- 중복 생성 최종 방어: `active_reservation_key` UNIQUE(슬롯당 활성 1건) + 토큰 1개 → 이중 방어.

### D3. 결제 확정 — 락 + 만료검사 (§2.4, §6-3, P2-2, P2-3)
`POST /payments/{paymentId}/confirmation` (USER):
1. **소유자 검증** `payment.userId == auth.userId`(아니면 403)
2. `Payment` **PESSIMISTIC_WRITE 잠금**(동시 확정 직렬화, 1차 후보 승인과 동일)
3. 만료 검사: `now > reservation.expires_at` 이면 `EXPIRED` 처리 후 409
4. **단일 MySQL 트랜잭션**: Payment `READY→PAID`, Reservation `→CONFIRMED`, `visit_slot OPEN→RESERVED`
   - 최종 방어: `active_reservation_key` UNIQUE + 상태 조건부 update
5. 커밋 후 `cleanupSlotRedis(slotId)`
- 멱등: 이미 PAID/CONFIRMED 면 현재 상태 반환. 경쟁 실패는 409.

### D4. 만료 / 실패 / 마감 정리 (P1-2, P1-4, P2-2)
- **TTL 만료**: 토큰 자동 소멸 → sweep 이 다음 대기자 발급 + 만료 PENDING 을 `EXPIRED`/Payment `FAILED` 확정(D1). GET 조회 시 read-repair 로 만료 반영.
- **결제 실패** `POST /payments/{id}/failure`(USER, **소유자 검증**): Payment `FAILED`, Reservation `EXPIRED`, `cleanupSlotRedis` → 다음 대기자.
- **슬롯 마감** `DELETE /visit-slots/{id}`:
  - `RESERVED` → **마감 거부(409)**.
  - `OPEN` → `CLOSED` + `cleanupSlotRedis` + 진행 중 `PENDING_PAYMENT` 를 `EXPIRED`·Payment `FAILED` 정리.

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
