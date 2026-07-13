# Data Model: 예약 취소/환불 + 월별 정산 (3차)

MySQL. 금액 `BIGINT`(원), 시각 `DATETIME(6)`.

## Enums (변경/추가)
```
PaymentStatus      : READY, PAID, FAILED, REFUNDED          (REFUNDED 추가)
SettlementStatus   : PENDING, CONFIRMED, PAID               (신규)
ReservationStatus  : ... CANCELLED (기존, 취소 시 사용)
VisitSlotStatus    : ... OPEN (취소 시 RESERVED→OPEN 재개방)
```

## 1. refund (신규)
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| payment_id | BIGINT | FK payment.id, **UNIQUE**(결제당 환불 1건) |
| reservation_id | BIGINT | FK reservation.id |
| realtor_id | BIGINT | 정산 집계용 |
| refund_amount | BIGINT | 전액(= payment.amount) |
| reason | VARCHAR(255) | nullable |
| refunded_at | DATETIME(6) | 정산 집계 기준(월) |
| created_at | DATETIME(6) | |

Index: `(realtor_id, refunded_at)` 정산 집계용.

## 2. settlement (신규)
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| realtor_id | BIGINT | |
| settlement_month | VARCHAR(7) | YYYY-MM |
| total_payment_amount | BIGINT | paidAt 이 해당 월인 결제 합 |
| total_refund_amount | BIGINT | refundedAt 이 해당 월인 환불 합 |
| net_amount | BIGINT | total_payment - total_refund (참고 지표) |
| carry_over_in | BIGINT | 전월 이월 차감액(≥0) |
| platform_fee | BIGINT | `gross>0 ? floor(gross*0.20) : 0` (gross=결제-환불-carry_in) |
| carry_over_out | BIGINT | `max(0, -gross)` → 다음 달 carry_over_in |
| payout_amount | BIGINT | `max(0, gross - platform_fee)` |
| status | VARCHAR(20) | SettlementStatus, default PENDING |
| created_at / updated_at | DATETIME(6) | |

- **UNIQUE(realtor_id, settlement_month)** → 월별 중복 정산 방지.
- Index: `(status)`, `(settlement_month)`.
- **계산식 정본은 spec §3** (이월 먼저 차감 후 수수료, floor 절사). 위 컬럼 설명은 그 요약.

## 3. 기존 엔티티 변경
- **payment**: `PaymentStatus.REFUNDED` 추가. `refund()`는 **PAID→REFUNDED 만 허용, paidAt 유지**(정산이 paidAt 에 의존, 리뷰 P1). REFUNDED 상태에서 `/failure` 요청은 **409**(리뷰 P0-5).
- **PaymentRepository**: `findByReservationIdForUpdate(reservationId)` 추가 — 취소 시 Payment 를 먼저 잠금(락 순서 P→R→V, 리뷰 P0-4).
- **reservation**: `cancel()`(기존) — CANCELLED, active_reservation_key=null.
- **visit_slot**: `reopen()` 메서드 — RESERVED→OPEN.

## 관계
```
payment 1—0..1 refund
realtor 1—N settlement (월별)
reservation 1—0..1 refund (취소 시)
```

## 집계 쿼리 개요 (배치)
```
-- 대상 realtor 집합(합집합): 당월 결제 ∪ 당월 환불 ∪ 전월 carry_over_out>0
--   (당월 결제/환불이 없어도 전월 이월이 남은 realtor 는 포함)
결제: Σ payment.amount  WHERE status IN (PAID, REFUNDED) AND paidAt ∈ 월   GROUP BY realtor_id
환불: Σ refund.refund_amount WHERE refundedAt ∈ 월                          GROUP BY realtor_id
이월: SELECT realtor_id, carry_over_out FROM settlement
        WHERE settlement_month = 전월 AND carry_over_out > 0            -- 당월 carry_over_in
-- 결제/환불 없는 realtor 는 결제=환불=0, carry_over_in 만 반영
```
> 결제는 paidAt 기준(환불되었어도 그 달엔 결제 발생), 환불은 refundedAt 월에 차감 → 월 경계 정확.

## 정합성 포인트
- `refund.payment_id` UNIQUE → 중복 환불 0.
- `UNIQUE(realtor_id, settlement_month)` → 중복 정산 0.
- 취소: Payment→Reservation→VisitSlot 락 순서(2차와 동일)로 동시 취소/정산 경합 방지.
