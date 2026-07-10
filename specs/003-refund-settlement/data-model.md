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
| net_amount | BIGINT | total_payment - total_refund |
| platform_fee | BIGINT | net>0 ? round(net*0.2) : 0 |
| carry_over_in | BIGINT | 전월 이월 차감액(≥0) |
| carry_over_out | BIGINT | 당월 발생 이월액(≥0) → 다음 달 carry_over_in |
| payout_amount | BIGINT | max(0, net - fee - carry_over_in) |
| status | VARCHAR(20) | SettlementStatus, default PENDING |
| created_at / updated_at | DATETIME(6) | |

- **UNIQUE(realtor_id, settlement_month)** → 월별 중복 정산 방지.
- Index: `(status)`, `(settlement_month)`.

## 3. 기존 엔티티 변경
- **payment**: `PaymentStatus.REFUNDED` 추가. 환불 시 PAID→REFUNDED.
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
-- 대상 월(YYYY-MM)의 중개사별 결제/환불
결제: Σ payment.amount  WHERE status IN (PAID, REFUNDED) AND paidAt ∈ 월   GROUP BY realtor_id
환불: Σ refund.refund_amount WHERE refundedAt ∈ 월                          GROUP BY realtor_id
전월 carry_over_out → 당월 carry_over_in
```
> 결제는 paidAt 기준(환불되었어도 그 달엔 결제 발생), 환불은 refundedAt 월에 차감 → 월 경계 정확.

## 정합성 포인트
- `refund.payment_id` UNIQUE → 중복 환불 0.
- `UNIQUE(realtor_id, settlement_month)` → 중복 정산 0.
- 취소: Payment→Reservation→VisitSlot 락 순서(2차와 동일)로 동시 취소/정산 경합 방지.
