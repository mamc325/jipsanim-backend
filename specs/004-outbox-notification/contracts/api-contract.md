# API Contract: Outbox 기반 비동기 알림 (4차)

- Base `/api`, 공통 응답 래퍼/권한 표기 1차와 동일.
- 알림 발행은 **비동기(Outbox Worker)** — 도메인 API 는 4차로 인해 응답 스펙이 바뀌지 않는다(알림은 부수효과).

---

## 알림 (수신자)

### GET /api/me/notifications?unread=true&page=0  [USER/REALTOR/ADMIN]
- 본인(`recipient_user_id = auth.userId`) 알림. `unread=true` 면 미읽음만.
```json
// content[]:
{ "notificationId":7, "type":"VISIT_RESERVATION_CONFIRMED",
  "title":"예약이 확정되었습니다", "message":"8월 1일 14:00 방문 예약이 확정되었습니다.",
  "isRead":false, "createdAt":"2026-07-14T10:00:00" }
```

### PATCH /api/notifications/{notificationId}  [본인]
- 읽음 처리(부수효과 없는 플래그 갱신 → PATCH 컨벤션). body `{ "isRead": true }`.
```json
// res 200 { "notificationId":7, "isRead":true }
```
- 403 `FORBIDDEN`(본인 아님), 404 `NOT_FOUND`.

---

## Outbox 관리 (관리자)

### GET /api/admin/outbox-events?status=DEAD&page=0  [ADMIN]
- status 선택 필터(PENDING/PROCESSING/PUBLISHED/DEAD). 모니터링용.
```json
// content[]:
{ "outboxEventId":15, "aggregateType":"RESERVATION", "aggregateId":11,
  "eventType":"VISIT_RESERVATION_CONFIRMED", "status":"DEAD", "attempts":6,
  "nextRetryAt":"2026-07-14T16:00:00", "lastError":"sender timeout",
  "publishedAt":null, "createdAt":"2026-07-14T09:00:00" }
```

### POST /api/admin/outbox-events/{outboxEventId}/reprocess  [ADMIN]
- `DEAD → PENDING`(attempts=0, next_retry_at=now) → Worker 가 재발행.
```json
// res 200 { "outboxEventId":15, "status":"PENDING", "attempts":0 }
```
- 409 `INVALID_STATE`(DEAD 아님 — 재처리 대상 아님).

---

## 에러 코드 (4차 재사용)
| code | HTTP | 의미 |
| --- | --- | --- |
| INVALID_STATE | 409 | 재처리 대상이 DEAD 아님 |
| FORBIDDEN | 403 | 알림 수신자 아님 |
| NOT_FOUND | 404 | 알림/이벤트 없음 |

> 4차는 **도메인 API 계약을 변경하지 않는다** — 알림은 Outbox 를 통해 비동기로만 발생(원칙 IV).
