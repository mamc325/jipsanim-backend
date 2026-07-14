# Data Model: Outbox 기반 비동기 알림 (4차)

MySQL 8. 시각 `DATETIME(6)`, payload `JSON`(또는 TEXT).

## Enums
```
OutboxStatus       : PENDING, PROCESSING, PUBLISHED, DEAD
NotificationType   : VISIT_RESERVATION_CONFIRMED, VISIT_RESERVATION_CANCELLED,
                     REFUND_COMPLETED, SETTLEMENT_PAID, PROPERTY_APPROVED,
                     PROPERTY_REJECTED, WAITING_QUEUE_INVITATION_GRANTED
```

## 1. outbox_event (신규)
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| aggregate_type | VARCHAR(50) | 예: RESERVATION, SETTLEMENT, PROPERTY, WAITING |
| aggregate_id | BIGINT | 원 도메인 식별자 |
| event_type | VARCHAR(50) | NotificationType 과 매핑 |
| event_key | VARCHAR(120) | **UNIQUE** — producer 멱등(같은 사건 이벤트 1건). `{EVENT_TYPE}:{도메인식별자}` |
| payload | JSON | 수신자 user_id + 렌더링 데이터 |
| status | VARCHAR(20) | OutboxStatus, default PENDING |
| attempts | INT | default 0, 발행 실패 누적 |
| next_retry_at | DATETIME(6) | default now, 폴링 대상 시각(백오프) |
| processing_started_at | DATETIME(6) | nullable, 선점 시각 — PROCESSING 고착 복구 기준(리뷰 P0-1) |
| last_error | VARCHAR(500) | nullable, 최근 실패 사유 |
| published_at | DATETIME(6) | nullable, 발행 완료 시각 |
| created_at | DATETIME(6) | |

- **UNIQUE(event_key)** → 중복 이벤트 적재 0(producer 멱등).
- Index: **`(status, next_retry_at)`** — 폴링 선점 조회, **`(status, processing_started_at)`** — reaper 조회(PROCESSING 고착 복구).
- 폴링(선점): `WHERE status='PENDING' AND next_retry_at <= :now ORDER BY id LIMIT :n FOR UPDATE SKIP LOCKED`
  → 선점한 행 `status=PROCESSING, processing_started_at=now` (claim 트랜잭션).
- **고착 복구(reaper)**: `UPDATE ... SET status='PENDING' WHERE status='PROCESSING' AND processing_started_at < :now - RECLAIM_TIMEOUT` — 처리 중 crash 로 PROCESSING 에 멈춘 이벤트를 되살림.

## 2. notification (신규)
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| recipient_user_id | BIGINT | 수신자(user.id) |
| type | VARCHAR(50) | NotificationType |
| title | VARCHAR(150) | 렌더링 제목 |
| message | VARCHAR(500) | 렌더링 본문 |
| is_read | BOOLEAN | default false |
| outbox_event_id | BIGINT | **UNIQUE** — 소비 멱등(이벤트당 알림 1건) |
| created_at | DATETIME(6) | |

- **UNIQUE(outbox_event_id)** → 중복 배달 시 알림 중복 0.
- Index: `(recipient_user_id, is_read)` — 본인 미읽음 조회.

## 관계
```
outbox_event 1—0..1 notification (발행 성공 시 생성)
user 1—N notification (recipient)
```
- FK 강제 대신 컬럼 참조(느슨한 결합). aggregate_id 는 도메인별 해석.

## 정합성 포인트
- 적재: 도메인 상태 변경과 **동일 트랜잭션** 커밋(원자성, 원칙 IV).
- Producer 멱등: `outbox_event.event_key` UNIQUE → 같은 사건 이벤트 2건 방지(리뷰 P0-2).
- 소비 멱등: `notification.outbox_event_id` UNIQUE + `SKIP LOCKED`(선점) → 정확히 1회 알림.
- 고착 복구: `processing_started_at` 타임아웃 reaper 로 PROCESSING → PENDING(리뷰 P0-1).
- 백오프: `next_retry_at` 로 재시도 간격 제어(`attempts>=6` → `DEAD` 영구 실패 격리).

## event_key 예시 (producer 멱등 키)
```
VISIT_RESERVATION_CONFIRMED:{reservationId}
VISIT_RESERVATION_CANCELLED:{reservationId}
REFUND_COMPLETED:{paymentId}          # refund.payment_id UNIQUE·즉시 가용(refundId=DB생성값 회피, 리뷰 P2)
SETTLEMENT_PAID:{settlementId}
PROPERTY_APPROVED:{propertyId}
PROPERTY_REJECTED:{propertyId}
WAITING_QUEUE_INVITATION_GRANTED:{slotId}:{userId}:{invitationSeq}   # invitationSeq = Redis INCR(발급 generation)
```

## payload 예시
```json
// VISIT_RESERVATION_CONFIRMED
{ "recipientUserId": 10, "reservationId": 11, "slotStartTime": "2026-08-01T14:00:00" }
// SETTLEMENT_PAID
{ "recipientUserId": 42, "settlementId": 3, "settlementMonth": "2026-07", "payoutAmount": 360000 }
```
