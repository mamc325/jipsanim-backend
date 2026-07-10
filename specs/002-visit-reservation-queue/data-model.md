# Data Model: 방문 예약 대기열 (2차)

MySQL(durable) + Redis(대기열/예약권, 임시). 금액 `BIGINT`(원), 시각 `DATETIME(6)`.

## Enums
```
VisitSlotStatus    : OPEN, RESERVED, CLOSED, EXPIRED   (HELD 없음 — Redis 토큰으로 파생, §6-2)
ReservationStatus  : PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED
PaymentStatus      : READY, PAID, FAILED               (CANCELLED/REFUNDED 는 3차)
```

## Redis 키 (정본은 plan.md)
```
waiting:visit-slot:{slotId}  ZSET  member=userId, score=timestamp(ms)
waiting:slots                SET   sweep 대상 slotId
reservation-token:{slotId}   STR   value=userId, TTL=300s (슬롯당 1개)
```

## 1. visit_slot
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| property_id | BIGINT | FK property.id |
| start_time | DATETIME(6) | 방문 시작 |
| end_time | DATETIME(6) | 방문 종료 |
| status | VARCHAR(20) | VisitSlotStatus, default OPEN |
| created_at / updated_at | DATETIME(6) | |

Index: `(property_id, status)`. Unique: `(property_id, start_time)` 중복 슬롯 방지.

## 2. reservation
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| user_id | BIGINT | 예약자 user.id |
| property_id | BIGINT | FK property.id (조회 편의) |
| visit_slot_id | BIGINT | FK visit_slot.id |
| status | VARCHAR(20) | ReservationStatus, default PENDING_PAYMENT |
| confirmed_slot_key | BIGINT | **생성 컬럼**: status='CONFIRMED' → visit_slot_id, else NULL |
| reserved_at | DATETIME(6) | 생성 시각 |
| confirmed_at | DATETIME(6) | nullable |
| cancelled_at | DATETIME(6) | nullable |
| created_at / updated_at | DATETIME(6) | |

- **UNIQUE(confirmed_slot_key)**: 슬롯당 CONFIRMED 예약 1건 보장(만료/재시도로 PENDING·EXPIRED 다건 허용, 확정만 유일). — active_key 패턴 재사용.
- Index: `(user_id, status)`, `(visit_slot_id)`.

## 3. payment
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| reservation_id | BIGINT | FK reservation.id, UNIQUE(예약당 결제 1건) |
| user_id | BIGINT | 결제자 |
| realtor_id | BIGINT | 정산 대비(3차) |
| amount | BIGINT | mock 결제 금액(reservation.fee-amount) |
| status | VARCHAR(20) | PaymentStatus, default READY |
| paid_at | DATETIME(6) | nullable |
| created_at / updated_at | DATETIME(6) | |

## 관계
```
property 1—N visit_slot
visit_slot 1—0..N reservation (만료/재시도로 여러 건, CONFIRMED 는 최대 1)
reservation 1—1 payment
```

## 상태 전이 (요약, 상세 §2.3)
```
VisitSlot   : OPEN --결제확정--> RESERVED ;  OPEN --마감--> CLOSED ;  OPEN --시간경과--> EXPIRED
              (HELD 는 Redis 토큰 존재로 파생)
Reservation : PENDING_PAYMENT --confirm--> CONFIRMED
              PENDING_PAYMENT --만료/실패--> EXPIRED ;  --취소--> CANCELLED
Payment     : READY --confirm--> PAID ;  READY --fail--> FAILED
```

## 정합성 포인트
- `reservation.confirmed_slot_key` UNIQUE → 슬롯당 확정 1건(동시 확정 경쟁 최종 방어, 실패는 409).
- `payment.reservation_id` UNIQUE → 예약당 결제 1건.
- `visit_slot(property_id, start_time)` UNIQUE → 중복 슬롯 방지.
- 예약권(Redis) 1개 → 애초에 슬롯당 동시 예약 시도 1인으로 직렬화(1차 방어).
