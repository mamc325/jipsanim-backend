# 집사님(Jipsanim) ERD / 데이터 모델 명세

> 부동산(오피스텔) 매물 검증 + 방문 예약/정산 + 알림 백엔드. 실제 JPA 엔티티 기준.
> **DBMS**: MySQL 8 / **PK**: `BIGINT AUTO_INCREMENT` / **시간**: `DATETIME(6)`(JPA `LocalDateTime`) / **공통 컬럼**: `created_at`, `updated_at`(JPA Auditing, `BaseTimeEntity`).

## 📌 테이블 목록 (18개)

| # | 도메인 | 테이블명 | 설명 |
| --- | --- | --- | --- |
| 1 | 회원 | users | 회원(USER/REALTOR/ADMIN) |
| 2 | 회원 | realtor | 중개사 프로필(users 1:1) |
| 3 | 매물 | property | 매물(오피스텔) |
| 4 | 매물 | property_image | 매물 이미지 |
| 5 | 매물 | property_verification | 매물 검증 요청/결과 |
| 6 | 매물 | property_verification_reason | 검증 사유(다건) |
| 7 | 시세 | price_standard | 운영 시세 기준(ACTIVE 1건) |
| 8 | 시세 | price_standard_candidate | 배치 산출 시세 후보 |
| 9 | 시세 | price_standard_history | 시세 기준 변경 이력 |
| 10 | 시세 | price_standard_batch_job | 시세 배치 잡 |
| 11 | 예약 | visit_slot | 방문 슬롯 |
| 12 | 예약 | reservation | 방문 예약 |
| 13 | 예약 | payment | Mock 결제 |
| 14 | 정산 | refund | 취소 환불 |
| 15 | 정산 | settlement | 중개사 월별 정산 |
| 16 | 알림 | notification | 알림 |
| 17 | 알림 | outbox_event | Outbox 이벤트(알림 발행) |
| 18 | 운영 | external_api_call_log | 외부 API 호출 이력 |

---

## 🧩 핵심 설계 패턴 (먼저 읽기)

### 1) 앱 관리 부분 유니크 키 (App-managed Partial Unique)

MySQL은 PostgreSQL의 부분 유니크 인덱스(`UNIQUE ... WHERE`)를 지원하지 않는다. "그룹당 활성 1건"을 DB 레벨에서 강제하기 위해, **활성 상태일 때만 값이 채워지고 비활성이면 NULL이 되는 키 컬럼**에 일반 UNIQUE를 건다. (MySQL은 NULL 중복 허용)

| 테이블 | 키 컬럼 | 의미 |
| --- | --- | --- |
| `price_standard` | `active_key` (= 시군구+거래유형+매물유형 해시) | 조합당 **ACTIVE 시세 기준 1건** |
| `reservation` | `active_reservation_key` (= `visit_slot_id`, 활성일 때만) | **슬롯당 활성 예약 1건** |

- 활성 이탈(EXPIRED/CANCELLED/교체) 시 키를 `NULL`로 → 재사용 허용.

### 2) 멱등/중복 방지 UNIQUE

| 테이블 | UNIQUE | 방지 대상 |
| --- | --- | --- |
| `payment` | `reservation_id` | 예약당 결제 2건 |
| `refund` | `payment_id` | **중복 환불** |
| `settlement` | `(realtor_id, settlement_month)` | 월별 중복 정산 |
| `visit_slot` | `(property_id, start_time)` | 동일 시각 슬롯 중복 |
| `outbox_event` | `event_key` | **producer 멱등**(같은 사건 이벤트 2건) |
| `notification` | `outbox_event_id` | **consumer 멱등**(이벤트당 알림 2건) |

### 3) 소프트 삭제

별도 `deleted_at` 컬럼 대신 **상태 enum**으로 관리한다(예: `property.status = DELETED`, `price_standard.status = EXPIRED`). 로그성 테이블은 물리 보존.

---

## 🧑‍💼 회원 도메인

### 1. users (회원) ✅

로컬(이메일/비밀번호) 가입 회원. 역할(`role`)로 일반 사용자/중개사/관리자를 구분한다.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 회원 식별자 |
| email | VARCHAR(255) | NOT NULL, UNIQUE | 로그인 이메일 |
| password | VARCHAR(255) | NOT NULL | BCrypt 해시 |
| nickname | VARCHAR(50) | NOT NULL | 닉네임 |
| role | VARCHAR(20) | ENUM, NOT NULL | 권한 (USER/REALTOR/ADMIN) |
| created_at | DATETIME(6) | NOT NULL | 가입 일시 |
| updated_at | DATETIME(6) | NOT NULL | 수정 일시 |

**인덱스 및 제약조건 (Indexes & Constraints)**

- **유니크 인덱스**: `uk_users_email` (`email`) — 이메일 중복 가입 방지.

**열거형 (ENUM: Role)**

| 값 | 설명 |
| --- | --- |
| USER | 일반 사용자(방문 예약자) |
| REALTOR | 중개사(매물/슬롯 관리, 정산 수령) |
| ADMIN | 관리자(검증/시세/정산 운영) |

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 회원가입 | `INSERT`. REALTOR면 `realtor` 프로필 함께 생성 |
| 인증 | JWT Access Token 발급(`sub`=id, `role`) |

---

### 2. realtor (중개사 프로필) ✅

REALTOR 역할 사용자의 중개사 부가 정보. `users`와 1:1.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 중개사 식별자 |
| user_id | BIGINT | FK → users.id, NOT NULL, UNIQUE | 소속 회원 ID(1:1) |
| business_name | VARCHAR(100) | NOT NULL | 상호명 |
| phone | VARCHAR(30) | NULL | 연락처 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **유니크 인덱스**: `uk_realtor_user` (`user_id`) — 회원당 중개사 프로필 1건.
- **외래키**: `realtor.user_id` → `users.id`.

**연관 관계**

- `realtor` 1:N `property` (한 중개사가 여러 매물 소유)
- `realtor.id` 는 `payment.realtor_id`, `refund.realtor_id`, `settlement.realtor_id` 의 정산 집계 기준.

---

## 🏢 매물 도메인

### 3. property (매물) ✅

중개사가 등록한 오피스텔 매물. 등록(DRAFT) → 검증 요청(PENDING) → 관리자 승인(ACTIVE)/반려(REJECTED) 생명주기를 가진다. 삭제는 상태(`DELETED`) 기반.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 매물 식별자 |
| realtor_id | BIGINT | FK → realtor.id, NOT NULL | 소유 중개사 |
| title | VARCHAR(200) | NOT NULL | 제목 |
| description | TEXT | NULL | 설명 |
| road_address | VARCHAR(255) | NULL | 도로명 주소 |
| bjdong_code | VARCHAR(10) | NULL | 법정동 코드 |
| sigungu_code | VARCHAR(5) | NULL | 시군구 코드(bjdong_code 앞 5자리 파생) |
| region_name | VARCHAR(100) | NULL | 지역명 |
| near_station | VARCHAR(100) | NULL | 인근 역 |
| property_type | VARCHAR(20) | ENUM, NOT NULL | 매물 유형(OFFICETEL) |
| deal_type | VARCHAR(20) | ENUM, NOT NULL | 거래 유형(JEONSE/MONTHLY_RENT) |
| deposit | BIGINT | NULL | 보증금(원) |
| monthly_rent | BIGINT | NULL | 월세(원) |
| area | DECIMAL(7,2) | NULL | 전용면적(㎡) |
| room_count | INT | NULL | 방 개수 |
| status | VARCHAR(20) | ENUM, NOT NULL | 매물 상태 |
| verification_status | VARCHAR(20) | ENUM, NULL | 검증 상태 |
| risk_level | VARCHAR(10) | ENUM, NULL | 가격 리스크 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- `idx_property_search` (`status, sigungu_code, deal_type, property_type`) — 사용자 조건 검색(QueryDSL) 최적화(선행 `status`로 ACTIVE만 탐색).
- `idx_property_realtor` (`realtor_id`) — 중개사별 매물 조회.
- **외래키**: `property.realtor_id` → `realtor.id`.
- `property` 1:N `property_image`(cascade, orphanRemoval).

**열거형 (ENUMs)**

| ENUM | 값 |
| --- | --- |
| PropertyType | OFFICETEL |
| DealType | JEONSE(전세), MONTHLY_RENT(월세) |
| PropertyStatus | DRAFT, PENDING, ACTIVE, REJECTED, CLOSED, HIDDEN, DELETED |
| VerificationStatus | PENDING, APPROVED, REJECTED, REVIEW_REQUIRED |
| RiskLevel | LOW, MEDIUM, HIGH |

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 등록 | `INSERT`(status=DRAFT). `bjdong_code`에서 `sigungu_code` 파생 |
| 검증 요청(submission) | 검증 엔진 실행 → `status=PENDING`, `verification_status`/`risk_level` 산정, `property_verification` 생성 |
| 관리자 승인 | `status=ACTIVE`, `verification_status=APPROVED` + Outbox `PROPERTY_APPROVED` 적재 |
| 관리자 반려 | `status=REJECTED`, `verification_status=REJECTED` + Outbox `PROPERTY_REJECTED` 적재 |
| 수정 | DRAFT/PENDING 상태에서만 허용 |
| 삭제 | softDelete → `status=DELETED` |

---

### 4. property_image (매물 이미지) ✅

매물의 이미지 목록. 매물과 N:1, cascade 삭제.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 이미지 식별자 |
| property_id | BIGINT | FK → property.id, NOT NULL | 소속 매물 |
| image_url | VARCHAR(500) | NOT NULL | 이미지 URL |
| is_primary | BOOLEAN | NOT NULL | 대표 이미지 여부 |
| sort_order | INT | - | 정렬 순서 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **외래키**: `property_image.property_id` → `property.id` (부모 매물과 cascade/orphanRemoval).

---

### 5. property_verification (매물 검증) ✅

매물 검증 요청과 자동 검증 결과, 관리자 심사 결과를 저장. 매물과 논리적 연관(`property_id` 컬럼 참조).

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 검증 식별자 |
| property_id | BIGINT | NOT NULL | 대상 매물 ID |
| requested_by | BIGINT | NULL | 요청자(중개사 user_id) |
| reviewed_by | BIGINT | NULL | 심사 관리자 user_id |
| status | VARCHAR(20) | ENUM, NOT NULL | 검증 상태 |
| risk_level | VARCHAR(10) | ENUM, NULL | 가격 리스크 |
| rejected_reason | VARCHAR(500) | NULL | 반려 사유 |
| reviewed_at | DATETIME(6) | NULL | 심사 일시 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 요청/수정 일시 |

**인덱스 및 제약조건**

- `idx_verification_status_risk` (`status, risk_level`) — 심사 대기열 필터.
- `idx_verification_property` (`property_id`) — 매물별 검증 조회.
- `property_verification` 1:N `property_verification_reason`(cascade).

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 검증 요청 | 검증 엔진 findings로 `status`(PENDING/REVIEW_REQUIRED)·`risk_level` 결정, `reason` 다건 생성 |
| 승인/반려 | `status` 갱신, `reviewed_by`/`reviewed_at` 기록. 매물 상태 동기화 |

---

### 6. property_verification_reason (검증 사유) ✅

검증 엔진이 산출한 개별 사유(다건). 검증과 N:1.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 사유 식별자 |
| verification_id | BIGINT | FK → property_verification.id, NOT NULL | 소속 검증 |
| reason_type | VARCHAR(40) | ENUM, NOT NULL | 사유 유형 |
| message | VARCHAR(500) | NULL | 사유 메시지 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**열거형 (ENUM: ReasonType)**

| 값 | 설명 |
| --- | --- |
| MISSING_REQUIRED_FIELD | 필수 항목 누락 |
| MISSING_IMAGE | 이미지 없음 |
| DESCRIPTION_TOO_SHORT | 설명 너무 짧음 |
| PRICE_OUT_OF_RANGE | 가격이 시세 범위 밖 |
| ADDRESS_REGION_MISMATCH | 주소/지역 불일치 |
| DUPLICATE_SUSPECTED | 중복 매물 의심 |
| INSUFFICIENT_STANDARD | 해당 지역 ACTIVE 시세 기준 없음/표본 부족 → 관리자 검토 |

---

## 📈 시세 도메인

> 국토교통부 실거래가를 배치로 수집→통계(p10~p90/IQR) 산출→**후보** 생성→관리자 승인→**운영 시세 기준**으로 승격. 매물 검증이 이 기준을 참조한다.

### 7. price_standard (운영 시세 기준) ✅

시군구×매물유형×거래유형 조합의 운영 중인 가격 범위 기준. **조합당 ACTIVE 1건**을 앱 관리 유니크 키(`active_key`)로 보장한다.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 기준 식별자 |
| sigungu_code | VARCHAR(5) | NOT NULL | 시군구 코드 |
| region_name | VARCHAR(100) | NULL | 지역명 |
| property_type | VARCHAR(20) | ENUM, NOT NULL | 매물 유형 |
| deal_type | VARCHAR(20) | ENUM, NOT NULL | 거래 유형 |
| min_deposit / max_deposit | BIGINT | NULL | 보증금 하한/상한 |
| min_monthly_rent / max_monthly_rent | BIGINT | NULL | 월세 하한/상한 |
| sample_count | INT | - | 산출 표본 수 |
| data_status | VARCHAR(20) | ENUM, NOT NULL | 표본 충분 여부 |
| source | VARCHAR(50) | NULL | 데이터 출처(MOLIT 등) |
| status | VARCHAR(20) | ENUM, NOT NULL | ACTIVE/EXPIRED |
| effective_from / effective_to | DATETIME(6) | NULL | 유효 기간 |
| active_key | VARCHAR(64) | UNIQUE | **앱 관리 부분 유니크 키**(ACTIVE일 때만 값, EXPIRED면 NULL) |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **유니크 인덱스**: `uk_price_standard_active` (`active_key`) — 조합당 ACTIVE 1건.

**열거형 (ENUMs)**

| ENUM | 값 |
| --- | --- |
| DataStatus | SUFFICIENT, INSUFFICIENT_DATA |
| PriceStandardStatus | ACTIVE, EXPIRED |
| CalcMethod | PERCENTILE([p10,p90]), IQR([Q1−1.5·IQR, Q3+1.5·IQR] 이상치 제외) |

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 후보 승인 | 기존 ACTIVE 기준을 EXPIRED(+`active_key`=NULL)로, 새 기준을 ACTIVE(+`active_key` 설정)로 교체 → `price_standard_history` 기록 |
| 매물 검증 | 매물의 시군구/거래유형/매물유형으로 ACTIVE 기준 조회 → 범위 밖이면 `PRICE_OUT_OF_RANGE`, 없으면 `INSUFFICIENT_STANDARD` |

---

### 8. price_standard_candidate (시세 후보) ✅

배치가 산출한 승인 대기 후보. 관리자가 승인하면 `price_standard`로 승격된다.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 후보 식별자 |
| sigungu_code | VARCHAR(5) | NOT NULL | 시군구 코드 |
| region_name | VARCHAR(100) | NULL | 지역명 |
| property_type / deal_type | VARCHAR(20) | ENUM, NOT NULL | 매물/거래 유형 |
| calc_min_deposit / calc_max_deposit | BIGINT | NULL | 산출 보증금 범위 |
| calc_min_monthly_rent / calc_max_monthly_rent | BIGINT | NULL | 산출 월세 범위 |
| calc_method | VARCHAR(20) | ENUM, NULL | 산출 방식(PERCENTILE/IQR) |
| sample_count | INT | - | 표본 수 |
| data_status | VARCHAR(20) | ENUM, NOT NULL | 표본 충분 여부 |
| source | VARCHAR(50) | NULL | 출처 |
| calculated_month | VARCHAR(7) | NULL | 산출 기준 월(YYYY-MM) |
| status | VARCHAR(20) | ENUM, NOT NULL | PENDING/APPROVED/REJECTED |
| reviewed_at | DATETIME(6) | NULL | 심사 일시 |
| reviewed_by | BIGINT | NULL | 심사 관리자 |
| batch_job_id | BIGINT | NULL | 산출 배치 잡 ID |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- `idx_candidate_status` (`status, sigungu_code`) — 승인 대기 목록 필터.

**열거형 (ENUM: CandidateStatus)**: PENDING, APPROVED, REJECTED

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 배치 산출 | 시군구×유형별 실거래가 통계 → 후보 생성(PENDING). 표본 부족 시 `data_status=INSUFFICIENT_DATA` |
| 승인 | **표본 부족 후보는 승인 불가(422)**. 아니면 `price_standard` 승격 + `status=APPROVED` |
| 반려 | `status=REJECTED` |

---

### 9. price_standard_history (시세 변경 이력) ✅

시세 기준이 교체될 때의 이전/이후 값 스냅샷.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 이력 식별자 |
| price_standard_id | BIGINT | NULL | 대상 기준 ID |
| sigungu_code | VARCHAR(5) | NULL | 시군구 코드 |
| property_type / deal_type | VARCHAR(20) | ENUM | 매물/거래 유형 |
| prev_min_deposit / prev_max_deposit / prev_min_monthly_rent / prev_max_monthly_rent | BIGINT | NULL | 변경 전 값 |
| new_min_deposit / new_max_deposit / new_min_monthly_rent / new_max_monthly_rent | BIGINT | NULL | 변경 후 값 |
| changed_by | BIGINT | NULL | 변경 관리자 |
| change_reason | VARCHAR(255) | NULL | 변경 사유 |
| created_at | DATETIME(6) | NOT NULL | 기록 일시 |

---

### 10. price_standard_batch_job (시세 배치 잡) ✅

국토부 실거래가 수집 배치의 실행 이력/상태.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 배치 잡 식별자 |
| job_month | VARCHAR(7) | NULL | 대상 월(YYYY-MM) |
| status | VARCHAR(20) | ENUM, NOT NULL | 실행 상태 |
| total_request_count | INT | - | 총 외부 호출 수 |
| success_count / fail_count | INT | - | 성공/실패 수 |
| started_at / finished_at | DATETIME(6) | NULL | 시작/종료 시각 |
| error_message | VARCHAR(1000) | NULL | 오류 메시지 |
| triggered_by | VARCHAR(20) | NULL | 실행 주체(ADMIN/SCHEDULER) |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**열거형 (ENUM: BatchStatus)**: RUNNING, SUCCESS, PARTIAL_FAILED, FAILED

**동기화**: 매월 스케줄러 또는 `POST /admin/price-standard-batch-jobs`(202)로 실행. 외부 호출마다 `external_api_call_log` 기록.

---

## 📅 예약 도메인

> **핵심 동시성 설계**: Redis Sorted Set 대기열 + Lua Script로 슬롯당 TTL 예약권 1개를 원자적으로 발급. DB에서는 **슬롯당 활성 예약 1건**을 앱 관리 유니크 키(`active_reservation_key`)로 보장.

### 11. visit_slot (방문 슬롯) ✅

중개사가 개설한 방문 가능 시간대. 상태 `OPEN/RESERVED/CLOSED/EXPIRED`.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 슬롯 식별자 |
| property_id | BIGINT | FK → property.id, NOT NULL | 소속 매물 |
| start_time | DATETIME(6) | NOT NULL | 방문 시작 시각 |
| end_time | DATETIME(6) | NOT NULL | 방문 종료 시각 |
| status | VARCHAR(20) | ENUM, NOT NULL | 슬롯 상태 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **유니크 인덱스**: `uk_visit_slot_time` (`property_id, start_time`) — 동일 매물 동일 시각 중복 방지.
- `idx_visit_slot_property` (`property_id, status`) — 매물별 예약 가능 슬롯 조회.

**열거형 (ENUM: VisitSlotStatus)**: OPEN, RESERVED, CLOSED, EXPIRED

> **HELD(임시 점유)는 DB 상태가 아님** — Redis 예약권(TTL 토큰)으로 관리. 결제 확정 시 `OPEN→RESERVED`, 취소 시 `RESERVED→OPEN` 재개방.

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 결제 확정 | `OPEN→RESERVED`(락 후 조건부) |
| 예약 취소 | `RESERVED→OPEN`(재개방) |
| 슬롯 마감 | `OPEN→CLOSED`(OPEN일 때만 조건부) |

---

### 12. reservation (방문 예약) ✅

사용자의 방문 예약. 예약권 보유자가 생성하며 결제 확정으로 성사된다.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 예약 식별자 |
| user_id | BIGINT | NOT NULL | 예약자 user_id |
| property_id | BIGINT | NOT NULL | 대상 매물 |
| visit_slot_id | BIGINT | NOT NULL | 대상 슬롯 |
| status | VARCHAR(20) | ENUM, NOT NULL | 예약 상태 |
| active_reservation_key | BIGINT | UNIQUE | **앱 관리 부분 유니크 키**(활성 시 `visit_slot_id`, 비활성 시 NULL) |
| expires_at | DATETIME(6) | NULL | 예약권 만료 시각(PENDING TTL) |
| reserved_at | DATETIME(6) | NOT NULL | 예약 생성 시각 |
| confirmed_at | DATETIME(6) | NULL | 확정 시각 |
| cancelled_at | DATETIME(6) | NULL | 취소 시각 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **유니크 인덱스**: `uk_reservation_active` (`active_reservation_key`) — **슬롯당 활성 예약 1건**.
- `idx_reservation_user` (`user_id, status`) — 내 예약 조회.
- `idx_reservation_slot` (`visit_slot_id`) — 슬롯별 예약.
- `idx_reservation_sweep` (`status, expires_at`) — 만료 예약 sweep 배치.

**열거형 (ENUM: ReservationStatus)**: PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 예약 생성 | `INSERT`(PENDING_PAYMENT, `active_reservation_key`=slot). `payment`(READY) 동시 생성 |
| 결제 확정 | `→CONFIRMED`, `confirmed_at` 기록. `active_reservation_key` 유지(확정도 활성) |
| 취소 | `→CANCELLED`, `active_reservation_key`=NULL(슬롯 재예약 허용) + Outbox 알림 |
| 결제 실패/만료 | `→EXPIRED`, `active_reservation_key`=NULL. sweep 배치가 만료 정리 |

---

### 13. payment (Mock 결제) ✅

예약 생성 시 함께 만들어지는 Mock 결제. 예약당 1건(UNIQUE).

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 결제 식별자 |
| reservation_id | BIGINT | NOT NULL, UNIQUE | 대상 예약 |
| user_id | BIGINT | NOT NULL | 결제자 |
| realtor_id | BIGINT | NULL | 정산 귀속 중개사 |
| amount | BIGINT | NOT NULL | 결제 금액(Mock 수수료) |
| status | VARCHAR(20) | ENUM, NOT NULL | 결제 상태 |
| paid_at | DATETIME(6) | NULL | 확정 시각(정산 집계 기준) |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **유니크 인덱스**: `uk_payment_reservation` (`reservation_id`) — 예약당 결제 1건.

**열거형 (ENUM: PaymentStatus)**: READY, PAID, FAILED, REFUNDED

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 결제 확정 | `READY→PAID`, `paid_at` 기록 + Outbox `VISIT_RESERVATION_CONFIRMED` |
| 결제 실패 | `READY→FAILED`(확정/환불 결제는 실패 불가) |
| 환불(취소) | `PAID→REFUNDED`(`paid_at` 유지 — 정산이 paidAt 의존) + `refund` 생성 |

---

## 💰 정산 도메인

### 14. refund (환불) ✅

예약 취소 시 발생하는 전액 환불(Mock). **결제당 환불 1건**(UNIQUE)으로 중복 환불을 원천 차단.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 환불 식별자 |
| payment_id | BIGINT | NOT NULL, UNIQUE | 대상 결제 |
| reservation_id | BIGINT | NOT NULL | 대상 예약 |
| realtor_id | BIGINT | NULL | 정산 귀속 중개사 |
| refund_amount | BIGINT | NOT NULL | 환불액(= 결제액 전액) |
| reason | VARCHAR(255) | NULL | 사유 |
| refunded_at | DATETIME(6) | NOT NULL | 환불 시각(정산 집계 기준 월) |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **유니크 인덱스**: `uk_refund_payment` (`payment_id`) — 중복 환불 방지(경쟁 시 409).
- `idx_refund_settlement` (`realtor_id, refunded_at`) — 정산 집계용.

**동기화**: 취소 트랜잭션에서 생성 → `payment.status=REFUNDED` + Outbox `REFUND_COMPLETED`.

---

### 15. settlement (중개사 월별 정산) ✅

중개사×월 단위 정산. 결제(paidAt)·환불(refundedAt)을 집계하고 수수료 20% 차감, 음수는 다음 달로 이월. **(realtor, month) UNIQUE**.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 정산 식별자 |
| realtor_id | BIGINT | NOT NULL | 중개사 |
| settlement_month | VARCHAR(7) | NOT NULL | 정산 월(YYYY-MM) |
| total_payment_amount | BIGINT | NOT NULL | 당월 결제 합(paidAt 기준) |
| total_refund_amount | BIGINT | NOT NULL | 당월 환불 합(refundedAt 기준) |
| net_amount | BIGINT | NOT NULL | 참고 지표(결제−환불) |
| carry_over_in | BIGINT | NOT NULL | 전월 이월 차감액(≥0) |
| platform_fee | BIGINT | NOT NULL | 플랫폼 수수료 |
| carry_over_out | BIGINT | NOT NULL | 당월 발생 이월액(≥0)→다음 달 |
| payout_amount | BIGINT | NOT NULL | 지급액 |
| status | VARCHAR(20) | ENUM, NOT NULL | 정산 상태 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **유니크 인덱스**: `uk_settlement_realtor_month` (`realtor_id, settlement_month`) — 월별 중복 정산 방지.
- `idx_settlement_status` (`status`), `idx_settlement_month` (`settlement_month`).

**열거형 (ENUM: SettlementStatus)**: PENDING, CONFIRMED, PAID

**계산식**

```
gross_available = total_payment - total_refund - carry_over_in   // 이월 먼저 차감
platform_fee    = gross > 0 ? floor(gross × 0.20) : 0            // 원 단위 절사
payout_amount   = max(0, gross - platform_fee)
carry_over_out  = max(0, -gross)                                 // 음수면 다음 달 이월
```

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 월별 배치 | 대상 realtor = 당월 결제 ∪ 당월 환불 ∪ 전월 carry_over_out>0. PENDING upsert. 같은 realtor 이후 월 존재 시 전체 409 |
| 관리자 확정 | `PENDING→CONFIRMED`(멱등) |
| 지급 | `CONFIRMED→PAID`(멱등, PENDING이면 409) + Outbox `SETTLEMENT_PAID` |

---

## 🔔 알림 / Outbox 도메인

> **Outbox Pattern**: 도메인 트랜잭션과 같은 커밋에 `outbox_event`를 적재하고, 폴링 Worker가 비동기 발행(알림 생성). producer/consumer 이중 멱등 + 지수 백오프 재시도 + DEAD 격리.

### 16. notification (알림) ✅

발행된 알림. **outbox_event_id UNIQUE**로 이벤트당 알림 1건(소비 멱등)을 보장.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 알림 식별자 |
| recipient_user_id | BIGINT | NOT NULL | 수신자 user_id |
| type | VARCHAR(50) | ENUM, NOT NULL | 알림 유형 |
| title | VARCHAR(150) | NOT NULL | 제목 |
| message | VARCHAR(500) | NOT NULL | 본문 |
| is_read | BOOLEAN | NOT NULL | 읽음 여부 |
| outbox_event_id | BIGINT | NOT NULL, UNIQUE | 원본 Outbox 이벤트(소비 멱등) |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **유니크 인덱스**: `uk_notification_outbox_event` (`outbox_event_id`) — 이벤트당 알림 1건.
- `idx_notification_recipient` (`recipient_user_id, is_read`) — 본인 미읽음 조회.

**열거형 (ENUM: NotificationType)**

| 값 | 발생 시점 | 수신자 |
| --- | --- | --- |
| VISIT_RESERVATION_CONFIRMED | 결제 확정 | 예약자 |
| VISIT_RESERVATION_CANCELLED | 예약 취소 | 예약자 |
| REFUND_COMPLETED | 환불 완료 | 예약자 |
| SETTLEMENT_PAID | 정산 지급 | 중개사 |
| PROPERTY_APPROVED | 매물 승인 | 중개사 |
| PROPERTY_REJECTED | 매물 반려 | 중개사 |
| WAITING_QUEUE_INVITATION_GRANTED | 예약권 발급(best-effort) | 발급 대상자 |

---

### 17. outbox_event (Outbox 이벤트) ✅

도메인 사건을 담는 Outbox. **event_key UNIQUE**(producer 멱등) + 상태/재시도 관리.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 이벤트 식별자 |
| aggregate_type | VARCHAR(50) | NOT NULL | 도메인 타입(RESERVATION/PAYMENT/SETTLEMENT/PROPERTY/WAITING) |
| aggregate_id | BIGINT | NOT NULL | 도메인 식별자 |
| event_type | VARCHAR(50) | NOT NULL | 이벤트 유형(= NotificationType 등) |
| event_key | VARCHAR(120) | NOT NULL, UNIQUE | **producer 멱등 키**({EVENT_TYPE}:{도메인식별자}...) |
| payload | JSON | NULL | 발행 데이터(recipientUserId 등) |
| status | VARCHAR(20) | ENUM, NOT NULL | 이벤트 상태 |
| attempts | INT | NOT NULL | 발행 실패 누적 |
| next_retry_at | DATETIME(6) | NOT NULL | 폴링 대상 시각(백오프) |
| processing_started_at | DATETIME(6) | NULL | 선점 시각(고착 복구 reaper 기준) |
| last_error | VARCHAR(500) | NULL | 최근 실패 사유 |
| published_at | DATETIME(6) | NULL | 발행 완료 시각 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- **유니크 인덱스**: `uk_outbox_event_key` (`event_key`) — producer 멱등(`ON DUPLICATE KEY UPDATE id=id` no-op).
- `idx_outbox_poll` (`status, next_retry_at`) — 폴링 선점 조회(FOR UPDATE SKIP LOCKED).
- `idx_outbox_reaper` (`status, processing_started_at`) — PROCESSING 고착 복구.

**열거형 (ENUM: OutboxStatus)**: PENDING, PROCESSING, PUBLISHED, DEAD

**동기화 및 비즈니스 로직**

| 이벤트 | 로직 |
| --- | --- |
| 적재 | 도메인 트랜잭션에서 native `INSERT ... ON DUPLICATE KEY UPDATE id=id`(멱등) |
| 폴링 발행 | Worker가 `PENDING & next_retry_at<=now`를 SKIP LOCKED 선점 → dispatch → `PUBLISHED` |
| 실패 | attempts++, 지수 백오프(1m·5m·15m·1h·6h), `attempts>=6`이면 `DEAD` |
| 고착 복구 | `PROCESSING & processing_started_at<타임아웃` → `PENDING` |
| 재처리 | 관리자 `DEAD→PENDING`(attempts=0) |

---

## 🛠️ 운영 도메인

### 18. external_api_call_log (외부 API 호출 이력) ✅

주소/실거래가 등 외부 API 호출 이력. 장애/성공률 모니터링용.

**컬럼 정의 (Columns)**

| 컬럼명 | 데이터 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AI | 로그 식별자 |
| api_type | VARCHAR(40) | ENUM, NOT NULL | 외부 API 유형 |
| request_url | VARCHAR(1000) | NULL | 요청 URL(키 마스킹) |
| request_params | VARCHAR(1000) | NULL | 요청 파라미터 |
| response_status | INT | NULL | HTTP 상태 |
| success | BOOLEAN | NOT NULL | 성공 여부 |
| error_message | VARCHAR(1000) | NULL | 오류 메시지 |
| elapsed_time_ms | INT | NULL | 소요 시간(ms) |
| batch_job_id | BIGINT | NULL | 연관 배치 잡 |
| called_at | DATETIME(6) | NOT NULL | 호출 시각 |
| created_at / updated_at | DATETIME(6) | NOT NULL | 생성/수정 일시 |

**인덱스 및 제약조건**

- `idx_call_log_type_time` (`api_type, called_at`) — 유형별 시계열 조회.
- `idx_call_log_batch` (`batch_job_id`) — 배치 잡별 호출 조회.

**열거형 (ENUM: ApiType)**: ADDRESS(주소), REAL_ESTATE_OFFICETEL_RENT(오피스텔 실거래가)

---

## 📐 공통 가정 및 정책

### 1. 데이터 타입 정책

| 항목 | 선택 | 근거 |
| --- | --- | --- |
| PK | `BIGINT AUTO_INCREMENT` | 사실상 무제한, 정렬/인덱스 효율 |
| 시각 | `DATETIME(6)`(JPA `LocalDateTime`) | 마이크로초 정밀, 애플리케이션 KST |
| 금액 | `BIGINT`(원 단위 정수) | 부동소수 오차 회피 |
| 면적 | `DECIMAL(7,2)` | ㎡ 소수 2자리 |
| enum | `VARCHAR` + `@Enumerated(STRING)` | 가독성·마이그레이션 유연성(코드값 아님) |
| payload | `JSON`(outbox) | 이벤트별 유연한 스키마 |
| 공통 | `created_at`/`updated_at` | `BaseTimeEntity` + JPA Auditing 자동 채움 |

### 2. FK / 연관관계 정책

- JPA `@ManyToOne`/`@OneToOne`로 실제 FK를 두는 경우: `realtor.user_id`, `property.realtor_id`, `property_image.property_id`, `property_verification_reason.verification_id`, `visit_slot.property_id`.
- **느슨한 결합(FK 미설정, id 컬럼 참조)**: 예약/결제/정산/알림 도메인 다수(`reservation.user_id`, `payment.reservation_id`, `settlement.realtor_id`, `notification.recipient_user_id`, `outbox_event.aggregate_id` 등) — 도메인 경계 분리·성능·유연성 목적.
- 삭제는 대부분 **상태 enum**(soft) 또는 cascade(이미지/사유 등 종속 데이터).

### 3. 동시성/정합성 핵심

| 메커니즘 | 위치 | 목적 |
| --- | --- | --- |
| 앱 관리 부분 유니크 키 | `price_standard.active_key`, `reservation.active_reservation_key` | 그룹당 활성 1건(MySQL 부분 유니크 대체) |
| 비관적 락(PESSIMISTIC_WRITE) | payment/reservation/visit_slot/settlement | 결제 확정·취소·정산 확정 경합 방지(락 순서 고정) |
| Redis Sorted Set + Lua | 대기열/예약권 | 슬롯당 TTL 토큰 1개 원자적 발급 |
| UNIQUE 최종 방어 | refund/payment/settlement/outbox/notification | 중복/멱등 경쟁의 마지막 방어선(→ 409) |
| Outbox + SKIP LOCKED | outbox_event | 알림 발행 원자성·다중 워커 선점 |

### 4. 인덱스 전략

- **검색 인덱스**: `idx_property_search`(status 선행으로 ACTIVE만 탐색 + 조건 컬럼).
- **폴링/스윕 인덱스**: `idx_reservation_sweep`(status,expires_at), `idx_outbox_poll`(status,next_retry_at), `idx_outbox_reaper`(status,processing_started_at).
- **집계 인덱스**: `idx_refund_settlement`(realtor_id,refunded_at), `idx_settlement_month`.

---

## 🔗 전체 관계 요약

```
users ─┬─ 1:1 ─ realtor ─ 1:N ─ property ─┬─ 1:N ─ property_image
       │                                   └─ 1:N ─ visit_slot ─(예약권/결제)─ reservation ─ 1:1 ─ payment
       │                                                                            │
       │  property ─ (property_id) ─ property_verification ─ 1:N ─ property_verification_reason
       │
       ├─ (user_id, 느슨) ─ reservation / notification(recipient)
       └─ (realtor_id, 느슨) ─ payment / refund / settlement

시세:  price_standard_batch_job ─ price_standard_candidate ─(승인)─ price_standard ─ price_standard_history
       (매물 검증이 ACTIVE price_standard 참조)

정산:  payment ─(취소)─ refund ─┐
       payment(paidAt)          ├─(월배치 집계)─ settlement
       refund(refundedAt)       ┘

알림:  도메인 트랜잭션 ─(같은 커밋)─ outbox_event ─(폴링 Worker)─ notification
                                    (event_key UNIQUE)        (outbox_event_id UNIQUE)

운영:  외부 API 호출 ─ external_api_call_log (batch_job_id로 price_standard_batch_job 연계)
```

### 삭제/보존 정책 요약

| 방식 | 대상 |
| --- | --- |
| 상태 기반 soft(별도 컬럼 없음) | property(DELETED), price_standard(EXPIRED), reservation(CANCELLED/EXPIRED), payment(REFUNDED/FAILED) |
| cascade(종속) | property_image, property_verification_reason |
| 물리 보존(로그성) | external_api_call_log, outbox_event(PUBLISHED/DEAD), price_standard_history |

---

> 본 ERD는 실제 JPA 엔티티(`@Table`/`@Column`/`@Index`/`@UniqueConstraint`)와 정합하며, 애플리케이션은 `ddl-auto` 기반으로 스키마를 관리합니다.
