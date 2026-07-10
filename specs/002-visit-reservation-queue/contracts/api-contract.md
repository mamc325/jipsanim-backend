# API Contract: 방문 예약 대기열 (2차)

- Base: `/api`, 공통 응답 래퍼/페이지네이션/권한 표기는 1차와 동일.
- REST 컨벤션(1차와 동일): 상태 전이 = 명사형 서브리소스 + POST.
- 결정 반영(§6): 결제는 예약 생성 시 동시 생성 → **별도 `POST /reservations/{id}/payments` 없음.**

---

## 방문 슬롯 (중개사)

### POST /api/properties/{propertyId}/visit-slots  [REALTOR, owner]
```json
// req { "startTime":"2026-07-20T14:00:00", "endTime":"2026-07-20T14:30:00" }
// res 201 { "visitSlotId": 5, "status": "OPEN" }
```
- 409 `INVALID_STATE`(동일 시각 슬롯 존재), 403 `NOT_OWNER`.

### GET /api/properties/{propertyId}/visit-slots  [PUBLIC]
```json
// res 200 [ { "visitSlotId":5,"startTime":"..","endTime":"..","status":"OPEN" } ]
```

### DELETE /api/visit-slots/{slotId}  [REALTOR, owner]  → status=CLOSED
- 409 `INVALID_STATE`(RESERVED 슬롯은 마감 불가).

---

## 대기열 (사용자)

### POST /api/visit-slots/{slotId}/waiting  [USER]  → 대기열 진입
```json
// res 201 { "slotId":5, "position": 3, "tokenGranted": false }
```
- 진입 직후 `tryIssue` 시도. 내가 즉시 선두면 `tokenGranted=true`.
- 409 `INVALID_STATE`(slot 이 OPEN 아님), 409 `ALREADY_WAITING`(중복 진입).

### GET /api/visit-slots/{slotId}/waiting/me  [USER]  → 내 순번/예약권
```json
// res 200 { "slotId":5, "position": 1, "tokenGranted": true, "tokenExpiresInSeconds": 287 }
```
- 조회 시 `tryIssue` 시도(멱등). 예약권 보유 시 `tokenGranted=true` + 남은 TTL.
- 404 `NOT_FOUND`(대기열에 없음).

---

## 예약 (사용자)

### POST /api/visit-slots/{slotId}/reservations  [USER]  → 예약 생성(+결제 READY)
```json
// res 201
{ "reservationId":11, "paymentId":9, "status":"PENDING_PAYMENT",
  "amount":10000, "expiresInSeconds":270 }
```
- 조건: 유효 예약권(token) 소유자 + slot `OPEN`. Reservation(PENDING_PAYMENT)+Payment(READY) 동시 생성.
- 403 `FORBIDDEN`(예약권 없음/불일치), 409 `INVALID_STATE`(slot 이 OPEN 아님).

### GET /api/me/reservations  [USER]
```json
// content[]:
{ "reservationId":11,"propertyId":10,"visitSlotId":5,"status":"CONFIRMED",
  "amount":10000,"reservedAt":"..","confirmedAt":".." }
```

---

## 결제 (사용자, Mock)

### POST /api/payments/{paymentId}/confirmation  [USER]  → 결제 확정→예약 확정
```json
// res 200
{ "paymentId":9,"reservationId":11,"paymentStatus":"PAID",
  "reservationStatus":"CONFIRMED","visitSlotStatus":"RESERVED" }
```
- 처리: 토큰 확인 → 트랜잭션(Payment READY→PAID, Reservation→CONFIRMED, slot OPEN→RESERVED, `confirmed_slot_key` UNIQUE) → 커밋 후 토큰/큐 삭제.
- 멱등: 이미 PAID/CONFIRMED 면 현재 상태 반환.
- 403 `FORBIDDEN`(토큰 불일치), 409 `INVALID_STATE`(READY 아님), 409 `ALREADY_REVIEWED`/`CONFLICT`(확정 경쟁 실패, `confirmed_slot_key` 위반).

### POST /api/payments/{paymentId}/failure  [USER]  → 결제 실패(슬롯 반환)
```json
// res 200 { "paymentId":9,"paymentStatus":"FAILED","reservationStatus":"EXPIRED" }
```
- 처리: Payment READY→FAILED, Reservation→EXPIRED, Redis 토큰 삭제 → sweep 이 다음 대기자 발급.

---

## 에러 코드 (2차 추가)
| code | HTTP | 의미 |
| --- | --- | --- |
| ALREADY_WAITING | 409 | 대기열 중복 진입 |
| INVALID_STATE | 409 | 슬롯/결제 상태 전이 불가 |
| CONFLICT | 409 | 확정 경쟁 실패(confirmed_slot_key) |
| FORBIDDEN | 403 | 예약권 없음/불일치 |

> 기존 공통 코드(VALIDATION_ERROR/NOT_FOUND/UNAUTHORIZED 등)는 1차 계약과 동일.
