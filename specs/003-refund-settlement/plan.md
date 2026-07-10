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

### D1. 예약 취소 → 환불 (§2-1, §2-2) — 락 순서 Payment→Reservation→VisitSlot
`POST /reservations/{id}/cancellation` (USER):
1. **Payment 를 reservationId 로 먼저 잠금** `paymentRepository.findByReservationIdForUpdate(reservationId)`(PESSIMISTIC_WRITE) — reservation 을 먼저 잠그지 않음(리뷰 P0-4)
2. `Reservation` 잠금 → 소유자 검증(reservation.userId==auth, 아니면 403)
3. **멱등**: 이미 CANCELLED → 현재 상태 반환
4. 검증: reservation `CONFIRMED` 아니면 409. **주입된 `Clock`** 으로 `slot.start_time <= now+24h` 이면 409(취소 불가)
5. `VisitSlot`(잠금) 재개방: RESERVED→OPEN(`reopen()`)
6. 트랜잭션: `Refund` 생성(refund_amount=payment.amount, refunded_at=now), `Payment.refund()`(PAID→REFUNDED, paidAt 유지), Reservation `cancel()`, VisitSlot `reopen()`
- 최종 방어: `refund.payment_id` UNIQUE(중복 환불 시 409 CONFLICT).
- **2차 코드 변경**: `PaymentService.fail()` 이 REFUNDED 상태를 409 로 막도록 수정(현재 READY 외 방치 — 리뷰 P0-5).

### D2. 월별 정산 배치 (§2-3, §2-4, §3)
`SettlementBatchService.run(month)` — **동기 실행, 결과 반환(200)**. (job 엔티티 없음, 리뷰 P0-3)
1. 대상 월의 중개사별 결제 합(paidAt∈월, status∈{PAID,REFUNDED}) / 환불 합(refundedAt∈월) 집계
2. 각 중개사: 전월 settlement.carry_over_out → carry_over_in
3. **계산식(§3, 이월 먼저 차감 후 수수료, floor 절사)** 로 gross_available/fee/payout/carry_over_out 산출
4. **선검사(집계 전체 순회)**: realtor 별로 *같은 realtor* 의 이후 월 settlement 존재 여부 확인 → **하나라도 존재하면 전체 요청 409**(부분 성공 없음, 단순 정책)
5. `Settlement(PENDING)` upsert — **UNIQUE(realtor_id, month)**:
   - 대상 월 PENDING & (같은 realtor) 이후 월 없음 → 재계산 갱신
   - CONFIRMED/PAID → skip
6. 결과 `{ month, createdCount, updatedCount, skippedCount }` 반환.
- 스케줄러: 매월 1일 04:00 전월 정산(테스트 비활성). 수동: `POST /admin/settlement-batch-jobs`(200).
- 월 계산은 주입된 `Clock` 기준.

### D3. 정산 확정/지급 (§4)
- `POST /admin/settlements/{id}/confirmation`: PENDING→CONFIRMED. CONFIRMED/PAID 재호출 → **200 현재 상태**.
- `POST /admin/settlements/{id}/payout`: CONFIRMED→PAID. PAID 재호출 → **200 현재 상태**, PENDING → **409**.

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
