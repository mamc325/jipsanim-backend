# API Contract: 예약 취소/환불 + 월별 정산 (3차)

- Base `/api`, 공통 응답 래퍼/권한 표기 1차와 동일. 상태 전이 = POST 액션 서브리소스.
- 결정 §2: 취소가 환불을 내부 생성 → **별도 `POST /payments/{id}/refunds` 없음.**

---

## 예약 취소/환불 (사용자)

### POST /api/reservations/{reservationId}/cancellation  [USER]
```json
// res 200
{ "reservationId":11, "reservationStatus":"CANCELLED", "paymentStatus":"REFUNDED",
  "refundAmount":10000, "visitSlotStatus":"OPEN" }
```
- 조건: 본인 예약(아니면 403), `CONFIRMED` 상태, **방문 24h 전**(`start_time > now+24h`).
- 처리: 락 Payment→Reservation→VisitSlot → Refund 생성 + Payment REFUNDED + Reservation CANCELLED + slot OPEN 재개방.
- 멱등: 이미 CANCELLED → 현재 상태 반환.
- 403 `FORBIDDEN`(본인 아님), 409 `INVALID_STATE`(CONFIRMED 아님 / 24h 이내), 409 `CONFLICT`(중복 환불).

---

## 정산 (중개사)

### GET /api/me/settlements?month=2026-07  [REALTOR]
```json
// content[] (month 미지정 시 전체):
{ "settlementId":3,"settlementMonth":"2026-07","totalPaymentAmount":500000,
  "totalRefundAmount":50000,"netAmount":450000,"platformFee":90000,
  "carryOverIn":0,"carryOverOut":0,"payoutAmount":360000,"status":"CONFIRMED" }
```

---

## 정산 관리 (관리자)

### POST /api/admin/settlement-batch-jobs  [ADMIN]  → 월별 정산 배치 실행
```json
// req(optional) { "month":"2026-07" }   // 미지정 시 전월
// res 202 { "month":"2026-07", "createdCount":12, "skippedCount":1 }
```
- 결제(paidAt)·환불(refundedAt) 집계 → Settlement(PENDING) upsert. CONFIRMED/PAID 는 skip.

### GET /api/admin/settlements?month=2026-07&realtorId=5&page=0  [ADMIN]
```json
// content[]: (위 정산 필드 + realtorId)
```

### PATCH... 아님 → POST /api/admin/settlements/{settlementId}/confirmation  [ADMIN]
```json
// res 200 { "settlementId":3,"status":"CONFIRMED" }
```
- 409 `INVALID_STATE`(PENDING 아님). 멱등: 이미 CONFIRMED → 현재 상태.

### POST /api/admin/settlements/{settlementId}/payout  [ADMIN]
```json
// res 200 { "settlementId":3,"status":"PAID","payoutAmount":360000 }
```
- 409 `INVALID_STATE`(CONFIRMED 아님).

---

## 에러 코드 (3차 추가/재사용)
| code | HTTP | 의미 |
| --- | --- | --- |
| INVALID_STATE | 409 | 취소 불가(상태/24h) / 정산 상태 전이 불가 |
| CONFLICT | 409 | 중복 환불(payment_id UNIQUE) |
| FORBIDDEN | 403 | 예약/정산 소유자 아님 |
| ALREADY_REVIEWED | 409 | 이미 확정된 정산 재확정 |
