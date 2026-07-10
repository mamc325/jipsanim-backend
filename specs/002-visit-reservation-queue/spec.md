# Feature Spec: 방문 예약 대기열 (2차)

- Feature Branch: `002-visit-reservation-queue`
- Status: Design Locked (구현 대기 — 1차 MVP 완료 후 착수)
- Constitution: v1.0.0

리뷰에서 확정한 설계 결정을 잃지 않도록 2차 착수 전에 미리 고정한다. 상세 plan/tasks 는 착수 시 작성.

## 1. 범위
- 중개사 방문 슬롯 등록/조회/삭제/마감
- Redis Sorted Set 대기열 + TTL 예약권(원자적 발급) + Mock 결제 확정 + 예약 확정
- **슬롯당 1명 확정 구조** (상위 K명 동시 발급 미채택)

## 2. Locked Decisions

### 2.1 슬롯당 1명 확정
- `UNIQUE(visit_slot_id)` 를 Reservation 확정의 최종 방어선으로 둔다.
- 예약권은 **한 번에 한 명에게만** 발급한다. 앞 순번 사용자가 TTL 안에 결제하지 못하면 예약권이 만료되고 다음 대기자에게 발급된다.
- 이력서 표현: "500명 동시 대기열 진입 시 **순번 정합성과 중복 예약 방지**를 검증" (500명 전원 예약 처리로 표현하지 않음).

### 2.2 예약권 발급 원자성 (Constitution II)
Worker 가 여러 인스턴스로 떠도 **슬롯당 active token 은 최대 1개**여야 한다.

- 대기열 key: `waiting:visit-slot:{slotId}` (ZSet, member=userId, score=요청 timestamp)
- 예약권 key: `reservation-token:{slotId}` (String, value=userId, TTL=5분)  ← 슬롯 단위 단일 키
- 발급은 **원자적 연산**으로만 수행한다. 순서가 중요하다 — 토큰에 넣을 userId 는 ZSET 에서 pop 해야 알 수 있으므로 `SET NX` 를 먼저 하면 안 된다. 택1:
  - **(A) Lua Script** (권장): 단일 스크립트로 아래를 원자 실행.
    1. `EXISTS reservation-token:{slotId}` → 이미 있으면(=발급 중) 즉시 종료(no-op).
    2. `ZPOPMIN waiting:{slotId}` 로 선두 userId pop. 큐가 비었으면 종료.
    3. `SET reservation-token:{slotId} <userId> PX 300000` 로 토큰 발급.
  - **(B) `ZPOPMIN` + `SET NX` (스크립트 미사용)**: 먼저 토큰 존재 확인, 없으면 `ZPOPMIN` 으로 userId 확보 후 `SET token <userId> NX PX 300000`. SET 이 실패하면(경쟁) pop 한 userId 를 큐에 되돌려 넣는 보상 필요 → A 대비 취약, **A 우선**.
- 결제 확정 또는 TTL 만료로 토큰이 사라진 뒤에만 다음 발급이 가능 → 자연스러운 직렬화.
- 대기 순번 조회: `ZRANK waiting:{slotId} {userId}` (+1).

### 2.3 상태 전이표

**VisitSlot** `OPEN → (HELD) → RESERVED` (+ 관리/마감)

> **정합성 결정(§6-2)**: HELD 는 MySQL 에 영속하지 않고 **"Redis 예약권 토큰 존재"로 파생**한다.
> MySQL `visit_slot.status` 는 `OPEN / RESERVED / CLOSED / EXPIRED` 만 가진다. 아래 표의 HELD 는 논리 상태(Redis).

| 현재 | 이벤트 | 다음 | 조건/비고 |
| --- | --- | --- | --- |
| OPEN | 예약권 발급(선두 사용자에게 token 부여) | HELD(논리) | 원자적 Lua 발급. Redis 토큰 생성, MySQL 불변 |
| HELD | 예약권 TTL 만료(미결제) | OPEN | 토큰 자동 소멸 → sweep/폴링이 다음 대기자에게 재발급 |
| HELD | 결제 확정(PAID) | RESERVED | MySQL OPEN→RESERVED, `UNIQUE(visit_slot_id)` 최종 방어 |
| HELD | 사용자 이탈/취소(결제 전) | OPEN | 토큰 삭제 → 재개방 |
| OPEN | 중개사 마감 | CLOSED | 신규 대기/발급 중단 |
| OPEN | 방문시각 경과 | EXPIRED | 스케줄러 정리 |

**Reservation** `PENDING_PAYMENT → CONFIRMED` (+ 예외)

| 현재 | 이벤트 | 다음 | 조건/비고 |
| --- | --- | --- | --- |
| (없음) | 예약권 보유자가 예약 생성 | PENDING_PAYMENT | 유효 token(=논리 HELD) + slot MySQL `OPEN`, `expires_at=now+토큰 잔여PTTL`. 슬롯당 활성 1건(active_reservation_key) |
| PENDING_PAYMENT | 결제 확정(PAID) | CONFIRMED | slot `OPEN→RESERVED` 동반, 멱등 |
| PENDING_PAYMENT | 예약권 TTL 만료 | EXPIRED | 토큰 소멸(논리 HELD 해제), slot `OPEN` 유지. sweep/read-repair 가 `now>expires_at` 로 EXPIRED 확정 + Payment FAILED |
| PENDING_PAYMENT | 결제 실패/취소 | EXPIRED | 토큰 삭제, slot `OPEN` 유지 |
| — | (3차) 확정 후 취소/환불 | CANCELLED | Refund/정산 연동은 3차 |

**Payment** (Mock, 2차): `READY → PAID` / `FAILED`. 확정은 `READY`일 때만(상태 조건부, 멱등).

### 2.4 예약 확정 조건 (Constitution II)
동시에 모두 만족해야 CONFIRMED:
1. 유효한 예약권(token, Redis) 이 존재하고 그 소유자와 일치
2. VisitSlot 이 아직 `RESERVED` 아님(MySQL `OPEN`)
3. Payment 가 `READY`(확정 대상)
4. 동일 `visit_slot_id` 로 확정 예약 없음 (`UNIQUE` 보장)
- **확정 처리**: 토큰(Redis) 확인 → **단일 MySQL 트랜잭션**에서 Reservation `CONFIRMED` + Payment `PAID` + `visit_slot.status OPEN→RESERVED`(`UNIQUE(visit_slot_id)` 최종 방어) → 커밋 후 Redis 토큰 삭제.
- 중복 결제 확정 요청이 들어와도 상태 기반 멱등 + UNIQUE 로 예약이 중복 생성되지 않는다(경쟁 실패는 409).

## 3. Entities (2차 신규, 상세는 착수 시)
VisitSlot, Reservation, Payment. (Refund/Settlement 은 3차)

## 4. 인수 기준(초안)
- [ ] 동일 슬롯에 다중 Worker 가 붙어도 active token 은 항상 1개.
- [ ] TTL 만료 시 slot 이 OPEN 으로 돌아가고 다음 대기자에게 발급된다.
- [ ] 500명 동시 진입 시 순번(ZRANK)이 정합적이고 확정 예약은 1건만 생성된다.
- [ ] 결제 확정 중복 요청에도 CONFIRMED 는 1회, slot 은 RESERVED 1회.

## 5. Notes
- 3차(환불/정산)에서 CONFIRMED 이후 취소·환불·월별 정산(환불 발생 월 차감 + 음수 정산 이월) 연결.

## 6. 착수 전 확정 결정 (2026-07-10)

### 6-1. 예약권 발급/재발급 트리거 → **폴링 on-demand + 스케줄러 sweep 백스톱**
- 발급 연산은 원자적 Lua `tryIssue(slotId)` = `EXISTS token ? no-op : (ZPOPMIN queue → SET token PX 300000)`.
- 트리거 지점(모두 멱등):
  1. 대기열 진입(`POST .../waiting`) 직후 `tryIssue`
  2. 순번 조회(`GET .../waiting/me`) 시 `tryIssue`
  3. 스케줄러 sweep(예: 2초 주기)이 활성 큐가 있는 슬롯에 `tryIssue` — **TTL 만료 후 재발급 백스톱**(요청이 안 와도 다음 대기자 발급)
- Redis keyspace notification 미채택: best-effort라 어차피 sweep 필요 → 복잡도만 증가.

### 6-2. hold 상태 권위 → **Redis=임시 hold 권위, MySQL=durable**
- HELD 는 MySQL 에 없음. "Redis 토큰 존재"로 파생(§2.3).
- MySQL `visit_slot.status ∈ {OPEN, RESERVED, CLOSED, EXPIRED}`.
- Redis↔MySQL 동기화 지점은 **예약 확정 1곳**뿐(OPEN→RESERVED). 발급/만료는 Redis 단독 → 부분실패 회피.

### 6-3. 결제 흐름 → **예약 생성 시 Payment 동시 생성**
- `POST /visit-slots/{slotId}/reservations`(토큰 보유자): Reservation `PENDING_PAYMENT`(+`expires_at`) + Payment `READY` 동시 생성. 같은 사용자가 반복 호출 시 **기존 예약 반환(멱등)**, 슬롯당 활성 1건은 `active_reservation_key` UNIQUE 로 보장(P1-1).
- `POST /payments/{id}/confirmation`(READY→PAID): 소유자 검증 + Payment 락 + 만료검사 → §2.4 확정 트랜잭션 → Reservation `CONFIRMED`, slot `RESERVED`, 토큰/큐 삭제. 멱등.

### 6-4. 정합성 정리 규칙 (리뷰 반영)
- **Redis 정리 2종**: `releaseTokenIfOwner(slotId,userId)`(token value==userId 일 때만 DEL, **큐 유지** — 실패/만료/read-repair) / `cleanupSlot`(token+큐+active-set 무조건 — 확정/마감/슬롯만료). 실패·만료에 큐를 지우면 다음 대기자 유실. **owner 체크 필수**: 늦게 도착한 A 처리가 이미 B 에게 발급된 토큰을 지우면 안 됨(리뷰-2).
- **발급 가드**: `tryIssueIfSlotOpen` — MySQL slot 이 OPEN 일 때만 발급, 아니면 `cleanupSlot`(P1-3).
- **만료 연결 + 잔여 TTL**: 예약 생성 시 `expires_at = now + 토큰 잔여시간(Redis PTTL)`(발급 후 경과 반영, 홀드 5분 초과 방지, 리뷰-1). sweep/read-repair/confirmation 에서 `now>expires_at` → `EXPIRED`+Payment `FAILED`(P1-2).
- **DB 락 순서(공통)**: `Payment → Reservation → VisitSlot`. confirm/sweep 모두 이 순서로 잠그고 상태 재확인 → 만료 경계에서 동시 실행돼도 하나만 성공(교착 없음)(리뷰-3).
- **sweep 순서**: ①만료 후보 락 후 재확인(PAID/CONFIRMED 면 skip) else EXPIRED+Payment FAILED → ②slot OPEN 확인 → ③tryIssueIfSlotOpen → ④빈 큐 active-set 제거.
- **확정 멱등 순서**: Payment 잠금 → 소유자 검증 → **이미 PAID면 토큰 검사 없이 반환** → READY면 토큰 검증 → 만료검사 → 확정. (커밋 후 토큰 삭제해도 재시도 200)
- **슬롯 마감**: RESERVED 거부(409), OPEN 은 CLOSED + `cleanupSlot` + PENDING EXPIRED/Payment FAILED(P1-4).
- **소유자 검증**: confirmation/failure 는 `payment.userId==auth.userId`(아니면 403)(P2-2). 동시 확정은 Payment PESSIMISTIC_WRITE(P2-3).

## 7. 다음 산출물 (착수)
`/plan` → `data-model`(visit_slot/reservation/payment) → `contracts`(슬롯·대기열·예약·결제 API) → `tasks`. 인프라: compose Redis + `spring-boot-starter-data-redis`.
