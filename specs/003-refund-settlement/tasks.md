# Tasks: 예약 취소/환불 + 월별 정산 (3차)

규칙: `[P]` 병렬 가능. 상태전이/계산은 테스트 먼저. 브랜치 `feat/003-p<phase>-*`.

## Phase 1. 엔티티/상태 확장
- [ ] T300 `Refund` 엔티티(payment_id UNIQUE, refunded_at) + RefundRepository
- [ ] T301 `Settlement` 엔티티(+SettlementStatus, UNIQUE(realtor_id, settlement_month)) + SettlementRepository
- [ ] T302 Payment 에 `REFUNDED` 추가 + `refund()`; VisitSlot `reopen()`(RESERVED→OPEN); ErrorCode 재사용 확인

## Phase 2. 예약 취소/환불
- [ ] T310 [P] 테스트: 24h 전 취소→환불/CANCELLED/slot OPEN, 24h 이내 409, 비CONFIRMED 409, 재취소 멱등, 중복환불 방지, 비소유자 403
- [ ] T311 `CancellationService`: 락 Payment→Reservation→VisitSlot, Refund 생성+Payment REFUNDED+Reservation CANCELLED+slot reopen (plan D1)
- [ ] T312 `POST /reservations/{id}/cancellation` 컨트롤러
- [ ] T313 [P] 통합: 취소 후 슬롯 재개방 → 다른 사용자 재예약 가능

## Phase 3. 정산 계산 + 배치
- [ ] T320 [P] 테스트: `SettlementCalculator` — net/fee(20%)/payout/carry_over(양수·음수·이월), **월 경계(7월 결제→8월 환불)**
- [ ] T321 `SettlementCalculator`(순수함수) — §3 계산식
- [ ] T322 집계 쿼리(결제 paidAt·환불 refundedAt 중개사별 합) + 전월 carry_over 조회
- [ ] T323 `SettlementBatchService.run(month)`: 집계→계산→Settlement(PENDING) upsert(UNIQUE, CONFIRMED/PAID skip)
- [ ] T324 스케줄러(매월 1일 04:00, 테스트 비활성) + `POST /admin/settlement-batch-jobs`
- [ ] T325 [P] 테스트: 배치 UNIQUE 중복정산 0, 재실행 skip, 이월 반영

## Phase 4. 정산 조회/확정/지급
- [ ] T330 `GET /me/settlements`(REALTOR), `GET /admin/settlements`(ADMIN, month/realtor 필터)
- [ ] T331 `POST /admin/settlements/{id}/confirmation`(PENDING→CONFIRMED, 멱등)
- [ ] T332 `POST /admin/settlements/{id}/payout`(CONFIRMED→PAID)
- [ ] T333 [P] 테스트: 확정/지급 상태전이·멱등

## Phase 5. 마감
- [ ] T340 통합 E2E: 예약확정→취소→환불→정산 배치→집계 반영→확정/지급
- [ ] T341 [P] docs/api-design 3차 갱신(/payments/{id}/refunds 제거), ROADMAP 3차 상태, 인수기준 체크

## 의존성
```
Phase1 → Phase2(취소/환불) → Phase3(정산 배치, 환불 데이터 필요) → Phase4(조회/확정) → Phase5
```
