# Implementation Plan: 예약 취소/환불 + 월별 정산 (3차)

- Branch prefix: `feat/003-p<phase>-*`
- Spec: `./spec.md` (§2 확정 결정)
- Constitution: v1.0.0

## Summary
CONFIRMED 예약을 방문 24h 전까지 취소→전액 환불→슬롯 재개방. 월별 배치가 결제(paidAt)/환불(refundedAt)을
집계해 수수료 20% 차감 정산을 생성(음수는 carry_over 로 이월). 관리자 확정/지급.

## Constitution Check
| 원칙 | 준수 |
| --- | --- |
| II. 멱등/원자성 | 취소 멱등(이미 CANCELLED 반환), refund.payment_id UNIQUE, 정산 UNIQUE(realtor,month), 상태전이 조건부 |
| V. 정합성 우선 | 취소는 단일 트랜잭션·락 순서 Payment→Reservation→VisitSlot(2차와 동일). 정산 배치는 트랜잭션 집계 |
| VI. 차수 분리 | 알림은 4차. 실결제/환불 없음(Mock) |
| VIII. 상태전이 테스트 | 취소·환불·정산 상태전이 + 월경계·이월 테스트 |

## 핵심 흐름

### D1. 예약 취소 → 환불 (§2-1, §2-2)
`POST /reservations/{id}/cancellation` (USER):
1. `Payment`(PESSIMISTIC_WRITE) 잠금 → `Reservation` 잠금 → 소유자 검증(reservation.userId==auth, 아니면 403)
2. **멱등**: 이미 CANCELLED → 현재 상태 반환
3. 검증: reservation `CONFIRMED` 아니면 409. `VisitSlot.start_time <= now+24h` 이면 409(취소 불가)
4. `VisitSlot`(잠금) 재개방: RESERVED→OPEN(`reopen()`)
5. 트랜잭션: `Refund` 생성(refund_amount=payment.amount, refunded_at=now), Payment `PAID→REFUNDED`, Reservation `CANCELLED`(active_key null), VisitSlot `OPEN`
- 최종 방어: `refund.payment_id` UNIQUE(중복 환불 시 409 CONFLICT).

### D2. 월별 정산 배치 (§2-3, §2-4, §3)
`SettlementBatchService.run(month)`:
1. 대상 월의 중개사별 결제 합(paidAt∈월, status∈{PAID,REFUNDED}) / 환불 합(refundedAt∈월) 집계
2. 각 중개사: 전월 settlement.carry_over_out → carry_over_in
3. 계산식(§3)으로 net/fee/payout/carry_over_out 산출
4. `Settlement(PENDING)` upsert — **UNIQUE(realtor_id, month)**. 이미 있으면 재계산 정책: PENDING 이면 갱신, CONFIRMED/PAID 면 skip(확정된 정산 불변)
- 스케줄러: 매월 1일 04:00 전월 정산. 수동: `POST /admin/settlement-batch-jobs`.

### D3. 정산 확정/지급 (§4)
- `POST /admin/settlements/{id}/confirmation`: PENDING→CONFIRMED(멱등: 이미 CONFIRMED/PAID → 현재 반환/409)
- `POST /admin/settlements/{id}/payout`: CONFIRMED→PAID(PENDING 이면 409)

## Architecture / Package
```
com.jipsanim.settlement
├─ domain      (Refund, Settlement + SettlementStatus)
├─ repository  (RefundRepository, SettlementRepository)
├─ cancel      (CancellationService: 취소+환불 트랜잭션)
├─ batch       (SettlementBatchService + Scheduler + 집계 쿼리)
├─ service     (SettlementAdminService: 확정/지급)
└─ controller  (cancellation / settlement / admin)
```
(Payment 에 REFUNDED, VisitSlot 에 reopen() 추가 — 기존 패키지 수정)

## Testing Strategy
- 단위: 정산 계산기(net/fee/carry_over: 양수·음수·이월 케이스), 월 경계(7월 결제→8월 환불)
- 통합(Testcontainers): 취소→환불→슬롯 재개방→재예약 가능, 24h 경계 409, 중복 환불 방지, 배치 UNIQUE·재실행 skip
- 정산 확정/지급 멱등

## Phasing
1. Refund/Settlement 엔티티 + Payment REFUNDED + VisitSlot reopen
2. 예약 취소/환불 (CancellationService + API)
3. 정산 계산기 + 배치 + 스케줄러/수동 트리거
4. 정산 조회/확정/지급 API
5. 통합 + 월경계/이월 테스트

## Complexity Tracking
- 없음(2차 스택·패턴 재사용).
