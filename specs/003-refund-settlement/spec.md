# Feature Spec: 예약 취소/환불 + 월별 정산 (3차)

- Feature Branch prefix: `feat/003-p<phase>-*`
- Status: Design Locked (구현 대기)
- Constitution: v1.0.0
- 선행: 2차(예약/결제) 완료

## 1. 범위
- 예약 취소(사용자) → 전액 환불(Mock) → 슬롯 재개방
- 중개사 월별 정산: 결제/환불 집계, 플랫폼 수수료 20%, 음수 정산 이월
- Out of scope: 실결제/실환불(Mock), 세금계산서, 알림(4차 Outbox)

## 2. 확정 결정 (2026-07-10)

### 2-1. 환불 정책 → **방문 24h 전까지 전액 환불, 이후 취소 불가**
- `visit_slot.start_time > now + 24h` 일 때만 취소·환불 가능(아니면 409).
- Mock 결제이므로 **환불액 = 결제액 전액**(부분 환불 없음).

### 2-2. 취소 시 슬롯 → **RESERVED → OPEN 재개방**
- 취소된 슬롯을 다시 열어 다른 사용자가 예약 가능. `active_reservation_key` 는 CANCELLED 로 null 화 → 재예약 허용.

### 2-3. 정산 생성 → **월별 배치 자동 생성 + 관리자 확정/지급**
- 매월 1일 배치가 전월(중개사×월) 정산을 `PENDING` 으로 생성. 관리자가 `CONFIRMED`→`PAID` 진행. 수동 트리거 API 도 제공.
- 집계 기준(기존 결정): **결제는 paidAt, 환불은 refundedAt 기준**. **환불은 발생 월에 차감.**

### 2-4. 음수 정산 이월 → **carry_over 컬럼으로 다음 달 시작 차감**
- 정산액이 음수면 `payout=0`, 미차감분을 `carry_over_out` 에 기록 → 다음 달 `carry_over_in` 으로 시작 차감.

## 3. 계산식 (정산)
**이월(carry_over_in)을 먼저 차감한 뒤 수수료를 매긴다** — 음수 이월을 갚는 달에 과다 수수료 방지(리뷰 P0-1).
```
total_payment   = Σ payment.amount        (status IN (PAID, REFUNDED), paidAt ∈ 월)
total_refund    = Σ refund.refund_amount   (refundedAt ∈ 월)
carry_over_in   = 전월 settlement.carry_over_out (없으면 0)

gross_available = total_payment - total_refund - carry_over_in     // 이월 먼저 차감
platform_fee    = gross_available > 0 ? floor(gross_available * 0.20) : 0   // 원 단위 절사(floor)
payout_amount   = max(0, gross_available - platform_fee)
carry_over_out  = max(0, -gross_available)  // 음수면 다음 달로 이월

net_amount      = total_payment - total_refund   // 기록용(참고 지표)
```
- **UNIQUE(realtor_id, settlement_month)** → 월별 중복 정산 방지.
- **과거 월 재정산 정책(리뷰 P0-2)**: 재계산 금지는 **realtor 단위**로 판정한다 — *같은 realtor* 에게 대상 월보다 이후 월 settlement 가 존재하면 그 realtor 의 대상 월 재계산을 금지(carry_over 연쇄 갱신 회피). 대상 월 정산이 PENDING 이고 같은 realtor 의 이후 월이 없으면 재계산 갱신.
- **배치 실패 단위(단순 정책)**: 수동 배치에서 대상 realtor 중 **하나라도** 위 조건에 걸리면 **전체 요청을 409 로 실패**(부분 성공/blockedCount 미도입). 부분 성공이 필요해지면 그때 `blockedCount` 를 응답에 추가한다.

## 4. 상태 전이
```
Reservation : CONFIRMED --취소(24h 전)--> CANCELLED (active_reservation_key=null)
Payment     : PAID --환불--> REFUNDED
VisitSlot   : RESERVED --취소--> OPEN (재개방)
Settlement  : PENDING --관리자 확정--> CONFIRMED --지급--> PAID
```

## 5. 엔티티 (상세 data-model)
- **Refund**(신규): payment_id UNIQUE, reservation_id, refund_amount, reason, refunded_at.
- **Settlement**(신규): realtor_id, settlement_month, total_payment_amount, total_refund_amount, net_amount, platform_fee, carry_over_in, carry_over_out, payout_amount, status. UNIQUE(realtor_id, settlement_month).
- **Payment**: PaymentStatus 에 `REFUNDED` 추가.
- **VisitSlot**: `reopen()`(RESERVED→OPEN).
- **Reservation**: `cancel()`(기존, active_key null).

## 6. API (상세 contracts)
- `POST /api/reservations/{id}/cancellation` [USER] — 취소+환불(내부 Refund 생성). 별도 `/payments/{id}/refunds` 없음.
- `GET /api/me/settlements?month=YYYY-MM` [REALTOR]
- `POST /api/admin/settlement-batch-jobs` [ADMIN] — 월별 정산 배치 실행
- `GET /api/admin/settlements?month=&realtorId=` [ADMIN]
- `POST /api/admin/settlements/{id}/confirmation` [ADMIN] (PENDING→CONFIRMED)
- `POST /api/admin/settlements/{id}/payout` [ADMIN] (CONFIRMED→PAID)

## 7. 정합성/멱등 (구현 전 확정)
- **취소 락 순서**: `Payment → Reservation → VisitSlot`(2차와 동일). 멱등: 이미 CANCELLED → 현재 상태 반환. 24h 경계·미CONFIRMED 는 409.
- **환불 유일성**: `refund.payment_id` UNIQUE → 중복 환불 방지.
- **정산 유일성**: `UNIQUE(realtor_id, settlement_month)`. 배치 재실행: PENDING 이면 재계산 갱신, CONFIRMED/PAID 면 skip, 이후 월 존재 시 재계산 금지(§3).
- **정산 상태 전이 멱등**(ALREADY_REVIEWED 재사용 안 함 — 검증 도메인 용어, 리뷰 P0-6):
  - confirmation: `PENDING→CONFIRMED`. 이미 `CONFIRMED/PAID` 재호출 → **200 현재 상태**. (그 외 없음)
  - payout: `CONFIRMED→PAID`. 이미 `PAID` 재호출 → **200 현재 상태**. `PENDING` → **409 INVALID_STATE**.
- **취소 락 순서**: `Payment → Reservation → VisitSlot`. Payment 를 **reservationId 로 먼저 잠근다**(`findByReservationIdForUpdate`) — reservation 을 먼저 잠그지 않음(리뷰 P0-4).
- **Clock 주입**: 취소 24h 판정·정산 월 계산은 주입된 `Clock` 사용(테스트 안정성, 리뷰 P1).
- **Payment.refund()**: `PAID→REFUNDED` 만 허용, `paidAt` 유지(정산이 paidAt 에 의존). REFUNDED 상태에서 `/failure` 는 409(리뷰 P0-5, P1).

## 8. 인수 기준
- [ ] 24h 전 취소 → 환불 전액, Payment REFUNDED, 예약 CANCELLED, 슬롯 OPEN 재개방.
- [ ] 24h 이내 취소 → 409.
- [ ] 재취소 멱등, 중복 환불 방지(payment_id UNIQUE).
- [ ] 월별 배치: 결제(paidAt)·환불(refundedAt) 집계, 수수료 20%, UNIQUE로 중복 정산 0.
- [ ] **월 경계**: 7월 결제 → 8월 환불 시 8월 정산에서 차감, 음수면 carry_over 로 다음 달 이월(누락/중복 차감 0).
- [ ] 정산 확정/지급 상태 전이 멱등.

## 9. 다음 산출물
`plan` → `data-model` → `contracts` → `tasks`. (별도 인프라 없음 — 2차 스택 재사용)
