# Tasks: 예약 취소/환불 + 월별 정산 (3차)

규칙: `[P]` 병렬 가능. 상태전이/계산은 테스트 먼저. 브랜치 `feat/003-p<phase>-*`.

## Phase 1. 엔티티/상태 확장
- [x] T300 `Refund` 엔티티(payment_id UNIQUE, refunded_at) + RefundRepository
- [x] T301 `Settlement` 엔티티(+SettlementStatus, UNIQUE(realtor_id, settlement_month)) + SettlementRepository
- [x] T302 Payment `REFUNDED` 추가 + `refund()`(PAID→REFUNDED만, paidAt 유지); **PaymentRepository.findByReservationIdForUpdate**(락 finder, P0-4); **PaymentService.fail() REFUNDED→409**(P0-5); VisitSlot `reopen()`(RESERVED→OPEN)

## Phase 2. 예약 취소/환불
- [ ] T310 [P] 테스트: 24h 전 취소→환불/CANCELLED/slot OPEN, 24h 이내 409, 비CONFIRMED 409, 재취소 멱등, 중복환불 방지, 비소유자 403
- [ ] T311 `CancellationService`: **락 Payment(by reservationId)→Reservation→VisitSlot**, **주입 Clock**으로 24h 판정, Refund 생성+Payment REFUNDED+Reservation CANCELLED+slot reopen, 멱등(이미 CANCELLED 반환) (plan D1)
- [ ] T312 `POST /reservations/{id}/cancellation` 컨트롤러
- [ ] T313 [P] 통합: 취소 후 슬롯 재개방 → 다른 사용자 재예약 가능

## Phase 3. 정산 계산 + 배치
- [ ] T320 [P] 테스트: `SettlementCalculator` — **이월 먼저 차감 후 수수료**(carry_over 갚는 달 과다수수료 없음, P0-1), floor 절사, 음수→carry_over_out, **월 경계(7월 결제→8월 환불)**
- [ ] T321 `SettlementCalculator`(순수함수) — §3: `gross_available=결제-환불-carry_in`, `fee=floor(gross>0? gross*0.2:0)`, `payout=max(0,gross-fee)`, `carry_out=max(0,-gross)`
- [ ] T322 집계 쿼리(결제 paidAt·환불 refundedAt 중개사별 합) + **대상 realtor 합집합**(결제∪환불∪전월 carry_over_out>0, P0-1) + 전월 carry_over_out 조회
- [ ] T323 `SettlementBatchService.run(month)`(주입 Clock): 집계→**선검사(realtor별 이후월 존재→하나라도면 전체 409)**→계산→Settlement(PENDING) upsert. PENDING 재계산 갱신, CONFIRMED/PAID skip (P0-2)
- [ ] T324 스케줄러(매월 1일 04:00, 테스트 비활성) + `POST /admin/settlement-batch-jobs`(**동기 200** + createdCount/updatedCount/skippedCount, P0-3)
- [ ] T325 [P] 테스트: 배치 UNIQUE 중복정산 0, PENDING 재계산, **같은 realtor 이후월 존재→전체 409**(부분성공 없음), 이월 반영, **당월 결제/환불 없고 전월 carry_over_out>0 인 realtor 도 정산 생성**(P0-1)

## Phase 4. 정산 조회/확정/지급
- [ ] T330 `GET /me/settlements`(REALTOR), `GET /admin/settlements`(ADMIN, month/realtor 필터)
- [ ] T331 `POST /admin/settlements/{id}/confirmation`(PENDING→CONFIRMED, 멱등: CONFIRMED/PAID 재호출 200)
- [ ] T332 `POST /admin/settlements/{id}/payout`(CONFIRMED→PAID, 멱등: PAID 재호출 200, PENDING 409)
- [ ] T333 [P] 테스트: 확정/지급 상태전이 + 멱등(200 재호출)·PENDING payout 409

## Phase 5. 마감
- [ ] T340 통합 E2E: 예약확정→취소→환불→정산 배치→집계 반영→확정/지급
- [ ] T341 [P] docs/api-design 3차 갱신(/payments/{id}/refunds 제거), ROADMAP 3차 상태, 인수기준 체크

## 의존성
```
Phase1 → Phase2(취소/환불) → Phase3(정산 배치, 환불 데이터 필요) → Phase4(조회/확정) → Phase5
```
