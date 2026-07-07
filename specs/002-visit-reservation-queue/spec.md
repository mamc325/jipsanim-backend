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
- 발급은 **원자적 연산**으로만 수행한다. 택1:
  - **(A) Lua Script** (권장): `SET reservation-token:{slotId} NX` 성공 시에만 `ZPOPMIN waiting:{slotId}` 로 선두 pop → 해당 userId 를 토큰 값으로 기록. 토큰이 이미 있으면(NX 실패) 아무 것도 하지 않음. 전 과정 단일 스크립트로 원자 실행.
  - **(B) `SET NX` + `ZPOPMIN`**: `SET token NX PX 300000` 성공한 워커만 `ZPOPMIN` 수행. 실패한 워커는 skip. (pop 후 SET 사이 크래시 대비 보상 필요 → A 대비 취약, A 우선)
- 결제 확정 또는 TTL 만료로 토큰이 사라진 뒤에만 다음 발급이 가능 → 자연스러운 직렬화.
- 대기 순번 조회: `ZRANK waiting:{slotId} {userId}` (+1).

### 2.3 상태 전이표

**VisitSlot** `OPEN → HELD → RESERVED` (+ 관리/마감)

| 현재 | 이벤트 | 다음 | 조건/비고 |
| --- | --- | --- | --- |
| OPEN | 예약권 발급(선두 사용자에게 token 부여) | HELD | 원자적 발급 성공. active token 1개 |
| HELD | 예약권 TTL 만료(미결제) | OPEN | Worker/만료 감지 → 다음 대기자에게 재발급 |
| HELD | 결제 확정(PAID) | RESERVED | `UNIQUE(visit_slot_id)` 최종 방어 |
| HELD | 사용자 이탈/취소(결제 전) | OPEN | 토큰 회수 후 재개방 |
| OPEN/HELD | 중개사 마감 | CLOSED | 신규 대기/발급 중단 |
| OPEN | 방문시각 경과 | EXPIRED | 스케줄러 정리 |

**Reservation** `PENDING_PAYMENT → CONFIRMED` (+ 예외)

| 현재 | 이벤트 | 다음 | 조건/비고 |
| --- | --- | --- | --- |
| (없음) | 예약권 보유자가 예약 생성 | PENDING_PAYMENT | 유효 token + slot=HELD 필요 |
| PENDING_PAYMENT | 결제 확정(PAID) | CONFIRMED | slot HELD→RESERVED 동반, 멱등 |
| PENDING_PAYMENT | 예약권 TTL 만료 | EXPIRED | slot HELD→OPEN 동반 |
| PENDING_PAYMENT | 사용자 취소 | CANCELLED | slot HELD→OPEN |
| — | (3차) 확정 후 취소/환불 | CANCELLED | Refund/정산 연동은 3차 |

**Payment** (Mock, 2차): `READY → PAID` / `FAILED`. 확정은 `READY`일 때만(상태 조건부, 멱등).

### 2.4 예약 확정 조건 (Constitution II)
동시에 모두 만족해야 CONFIRMED:
1. 유효한 예약권(token) 이 존재하고 그 소유자와 일치
2. VisitSlot 이 `HELD` (해당 사용자 hold)
3. Payment 가 `PAID`
4. 동일 `visit_slot_id` 로 확정 예약 없음 (`UNIQUE` 보장)
- 중복 결제 확정 요청이 들어와도 상태 기반 멱등으로 예약/알림이 중복 생성되지 않는다.

## 3. Entities (2차 신규, 상세는 착수 시)
VisitSlot, Reservation, Payment. (Refund/Settlement 은 3차)

## 4. 인수 기준(초안)
- [ ] 동일 슬롯에 다중 Worker 가 붙어도 active token 은 항상 1개.
- [ ] TTL 만료 시 slot 이 OPEN 으로 돌아가고 다음 대기자에게 발급된다.
- [ ] 500명 동시 진입 시 순번(ZRANK)이 정합적이고 확정 예약은 1건만 생성된다.
- [ ] 결제 확정 중복 요청에도 CONFIRMED 는 1회, slot 은 RESERVED 1회.

## 5. Notes
- 상세 plan/data-model/contracts/tasks 는 1차 MVP 완료 후 `/plan` 부터 재개.
- 3차(환불/정산)에서 CONFIRMED 이후 취소·환불·월별 정산(환불 발생 월 차감 + 음수 정산 이월) 연결.
