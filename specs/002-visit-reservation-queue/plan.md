# Implementation Plan: 방문 예약 대기열 (2차)

- Branch prefix: `feat/002-p<phase>-*`
- Spec: `./spec.md` (§6 확정 결정 반영)
- Constitution: v1.0.0

## Summary
방문 슬롯(MySQL) + Redis Sorted Set 대기열 + TTL 예약권(원자적 Lua 발급) + Mock 결제로 슬롯당 1명 확정.
HELD 는 MySQL 에 두지 않고 Redis 토큰 존재로 파생(§6-2). Redis↔MySQL 동기화는 **예약 확정 1지점**뿐.

## 구현 전 확정 (Phase 2 진입 조건)
Phase 0~1(Redis 인프라·WaitingQueue·Lua)은 바로 착수 가능. Phase 2 이후는 아래 8건이 코드에 박혀 있어야 흔들리지 않음:
1. `active_reservation_key` 는 **앱 관리 컬럼**(1차 active_key 패턴, 생성 컬럼 아님) — data-model
2. `enqueue` 는 **Lua로 INCR+ZADD NX+SADD 원자화** — 위 Redis 설계
3. `confirm` 만료 시 **Reservation EXPIRED + Payment FAILED + releaseTokenIfOwner** — D3-5
4. `failure` **멱등**: FAILED 재호출=200, PAID 이후=409 — D4
5. `slot close` 는 confirm 과 **데드락 안 나게 락 순서/트랜잭션 분리** — D4
6. 슬롯 생성은 **ACTIVE 매물만, 과거/역전/겹침 시간 금지** — contract, T222
7. **ErrorCode 2차 코드 추가** + GlobalExceptionHandler **DataIntegrityViolation → 중립 CONFLICT(409)**(현재 ALREADY_REVIEWED 고정 수정) — T204
8. 위 항목 **테스트** 추가 — T221/T242/T250/T262

## Technical Context
- 추가 의존성: `spring-boot-starter-data-redis`
- 인프라: `docker-compose.yml` 에 `redis:7` 추가, `spring.data.redis.*` 설정
- Config: `reservation.token-ttl-seconds`(300), `reservation.sweep-interval-ms`(2000), `reservation.fee-amount`(mock 결제 금액)

## Constitution Check
| 원칙 | 준수 |
| --- | --- |
| II. 멱등/원자성 | 예약권 발급은 **단일 Lua**(EXISTS→ZPOPMIN→SET PX). 확정은 상태조건부 + `active_reservation_key` UNIQUE. 결제 확정 멱등 |
| V. 정합성 우선 | 확정만 MySQL 트랜잭션. Redis 는 큐/토큰(임시). 부분실패 지점 최소화 |
| VI. 차수 분리 | 환불/정산은 3차. Payment 는 READY/PAID/FAILED 까지 |
| VIII. 상태전이 테스트 | 발급 원자성·확정·만료 재발급·중복예약 방지 테스트 |

## Redis 설계
```
대기열   ZSET  waiting:visit-slot:{slotId}   member=userId, score=INCR waiting:seq (전역 시퀀스 → 강한 FIFO, ms 충돌 없음)
활성슬롯 SET   waiting:slots                 sweep 대상 slotId 집합
예약권   STR   reservation-token:{slotId}    value=userId, TTL=300s   ← 슬롯당 단일 키
시퀀스   STR   waiting:seq                   INCR 로 단조 증가하는 진입 순번
```

### Lua `enqueue(slotId, userId)` — 원자적 진입 (KEYS: queue, activeSlots, seq / ARGV: slotId, userId)
```lua
if redis.call('ZSCORE', KEYS[1], ARGV[2]) then return 0 end   -- 이미 큐에 있음(ALREADY_WAITING)
local seq = redis.call('INCR', KEYS[3])                         -- 전역 FIFO 시퀀스
redis.call('ZADD', KEYS[1], seq, ARGV[2])
redis.call('SADD', KEYS[2], ARGV[1])                            -- sweep 대상 등록
return seq
```
- INCR + ZADD + SADD 를 한 스크립트로 → 진입 원자화, 중복 진입 방지, FIFO 보장.

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

### D1. Redis 정리 2종 + 발급 가드 + sweep 순서 (§6-1, P1-3, 리뷰)
**정리 연산은 상황별로 다르다 — 실패/만료는 slot 이 아직 OPEN 이므로 큐를 유지한다:**
- `releaseTokenIfOwner(slotId, userId)`: **현재 token value == userId 일 때만 DEL**(queue+active-set 유지). → 결제 실패 / 예약 만료 / read-repair. **owner 체크 필수**(리뷰-2): A 만료 처리가 늦게 도착해 이미 B 에게 발급된 토큰을 지우면 안 됨.
  ```lua
  if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
  return 0
  ```
- `cleanupSlot(slotId)`: token + queue + active-set **전체 삭제**(무조건). → 확정(RESERVED) / 마감(CLOSED) / 슬롯 만료(EXPIRED) — slot 이 종료 상태라 안전.

`tryIssueIfSlotOpen(slotId)`: MySQL slot 이 `OPEN` 이면 `tryIssue`(Lua), 아니면 `cleanupSlot`.

멱등 호출 지점: ①대기열 진입 직후 ②순번 조회 시 ③sweep 스케줄러(2초).

**DB 락 순서(공통, 리뷰-3): `Payment → Reservation → VisitSlot`.** confirm/sweep 모두 이 순서로 잠근다(교착 방지).

**sweep(slotId) 순서 (P2 — 만료 PENDING 정리를 먼저)**:
```
1. 만료 후보(status=PENDING_PAYMENT AND expires_at<now) 각각:
   - 락(Payment→Reservation→VisitSlot) 후 상태 재확인
   - 이미 PAID/CONFIRMED 면 만료 처리 안 함(confirm 이 이겼음)
   - 아니면 Reservation EXPIRED + Payment FAILED (active_reservation_key NULL화; 안 하면 다음 예약 409)
2. slot 상태 확인(OPEN 여부)
3. tryIssueIfSlotOpen(slotId)
4. 큐·토큰 모두 비면 waiting:slots 에서 제거
```

### D2. 예약 생성 — 멱등 + 잔여 TTL 기준 (§6-3, P1-1, 리뷰-1)
`POST /visit-slots/{slotId}/reservations` (USER):
1. `reservation-token:{slotId}` == userId 검증(아니면 403) + **`PTTL` 조회 → `remainingTtl`**
2. **`remainingTtl <= 0` → 403**(예약권 만료). slot 이 MySQL `OPEN` 인지(아니면 409)
3. **멱등**: 해당 slot 에 이 사용자의 활성 `PENDING_PAYMENT` 예약이 있으면 **기존 예약/결제 반환**(신규 생성 금지)
4. 없으면 트랜잭션: `Reservation(PENDING_PAYMENT, expires_at = now + remainingTtl)` + `Payment(READY, amount=fee)` 생성
5. **`active_reservation_key` UNIQUE 충돌 시**(직전 사용자 A 의 만료 PENDING 이 아직 EXPIRED 로 정리 안 됨 — B 가 새 토큰을 받았어도 409 가능, 리뷰-2):
   → 해당 slot 의 만료 PENDING(`expires_at<now`)을 공통 락 순서로 `EXPIRED`+Payment `FAILED` 정리 후 **1회 재시도**. 재시도도 실패면 409.
- **`expires_at` 은 토큰 실제 잔여시간(remainingTtl)에 정렬** — now+full TTL 로 재부여하면 실제 홀드가 5분을 초과함(리뷰-1). 응답 `expiresInSeconds = remainingTtl`.
- 중복 생성 최종 방어: `active_reservation_key` UNIQUE(슬롯당 활성 1건) + 토큰 1개 → 이중 방어.

### D3. 결제 확정 — 멱등 우선 + 락 + 만료검사 (§2.4, §6-3, P1-b, P2-2, P2-3)
`POST /payments/{paymentId}/confirmation` (USER) — **하나의 `@Transactional`**(잠금~커밋 동일 범위), **락 순서 `Payment→Reservation→VisitSlot`**(sweep 과 동일):
1. `Payment` **PESSIMISTIC_WRITE 잠금 조회**
2. **소유자 검증** `payment.userId==auth.userId`(아니면 403)
3. **이미 `PAID`(예약 CONFIRMED) → 토큰 검사 없이 현재 상태 반환**(멱등)
4. `READY` 일 때만: **토큰 소유자 검증**(아니면 403)
5. `Reservation`(PESSIMISTIC_WRITE) 잠금 → 만료 검사 `now > expires_at` → **Reservation `EXPIRED` + Payment `FAILED` + `releaseTokenIfOwner`** 후 409(만료)
6. `VisitSlot` 잠금 → Payment `READY→PAID`, Reservation `→CONFIRMED`, `visit_slot OPEN→RESERVED`
   - 최종 방어: `active_reservation_key` UNIQUE + 상태 조건부 update
7. 커밋 후 `cleanupSlot(slotId)`(확정이므로 큐까지 삭제)
- 경쟁 실패는 409. sweep 과 동일 락 순서라 만료 직전 confirm/sweep 동시 실행 시 교착 없이 하나만 성공.

### D4. 만료 / 실패 / 마감 정리 (P1-2, P1-4, P2-2, 리뷰)
- **TTL 만료**: 토큰 자동 소멸(**큐 유지**) → sweep 이 만료 PENDING 을 `EXPIRED`/Payment `FAILED` 확정 후 다음 대기자 발급(D1). GET 조회 시 read-repair(`releaseTokenIfOwner`).
- **결제 실패** `POST /payments/{id}/failure`(USER, **소유자 검증**): **락 `Payment→Reservation`(PESSIMISTIC_WRITE) 후 상태 재확인**.
  - 이미 `FAILED` → **200(멱등)** 현재 상태 반환.
  - 이미 `PAID`(CONFIRMED) → **409**(확정된 결제는 실패 불가).
  - `READY` → Payment `FAILED` + Reservation `EXPIRED`, 커밋 후 **`releaseTokenIfOwner`(큐 유지)** → 다음 대기자.
- **슬롯 마감** `DELETE /visit-slots/{id}` (리뷰-3, 락 역순 방지):
  - `RESERVED` → **마감 거부(409)**.
  - `OPEN` → ① **짧은 조건부 update로 CLOSED 전이·커밋**(`UPDATE ... SET status=CLOSED WHERE id=? AND status=OPEN`) + `cleanupSlot`(Redis) → ② **별도 트랜잭션**에서 진행 중 `PENDING_PAYMENT` 를 **공통 락 순서(Payment→Reservation→VisitSlot)** 로 `EXPIRED`·Payment `FAILED` 정리. slot 을 먼저 잠그면 confirm/sweep 과 락 역순이 되어 교착 → 분리.

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
- 통합(Testcontainers MySQL + Redis): 예약권 1개 보장, 확정→RESERVED, 만료 후 재발급, 중복예약 방지(active_reservation_key)
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
