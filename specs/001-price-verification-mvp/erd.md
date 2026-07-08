# ERD: 실거래가 기반 시세 검증 MVP

컬럼 상세/타입/제약은 `data-model.md` 기준. 여기서는 관계 구조를 시각화한다.
(VisitSlot/Reservation/Payment 등 예약·결제는 2차 이후 — 본 ERD에는 미포함)

```mermaid
erDiagram
    USER ||--o| REALTOR : "has (role=REALTOR)"
    REALTOR ||--o{ PROPERTY : registers
    PROPERTY ||--o{ PROPERTY_IMAGE : has
    PROPERTY ||--o{ PROPERTY_VERIFICATION : requested_for
    PROPERTY_VERIFICATION ||--o{ PROPERTY_VERIFICATION_REASON : contains

    PRICE_STANDARD_BATCH_JOB ||--o{ PRICE_STANDARD_CANDIDATE : produces
    PRICE_STANDARD_BATCH_JOB ||--o{ EXTERNAL_API_CALL_LOG : logs
    PRICE_STANDARD_CANDIDATE ||--o| PRICE_STANDARD : "approved_into"
    PRICE_STANDARD ||--o{ PRICE_STANDARD_HISTORY : records

    USER {
        bigint id PK
        varchar email UK
        varchar password
        varchar nickname
        varchar role
    }
    REALTOR {
        bigint id PK
        bigint user_id FK,UK
        varchar business_name
        varchar phone
    }
    PROPERTY {
        bigint id PK
        bigint realtor_id FK
        varchar title
        varchar road_address
        varchar bjdong_code
        varchar sigungu_code
        varchar property_type
        varchar deal_type
        bigint deposit
        bigint monthly_rent
        varchar status
        varchar verification_status
        varchar risk_level
    }
    PROPERTY_IMAGE {
        bigint id PK
        bigint property_id FK
        varchar image_url
        boolean is_primary
    }
    PROPERTY_VERIFICATION {
        bigint id PK
        bigint property_id FK
        bigint requested_by
        bigint reviewed_by
        varchar status
        varchar risk_level
        varchar rejected_reason
    }
    PROPERTY_VERIFICATION_REASON {
        bigint id PK
        bigint verification_id FK
        varchar reason_type
        varchar message
    }
    PRICE_STANDARD {
        bigint id PK
        varchar sigungu_code
        varchar property_type
        varchar deal_type
        bigint min_deposit
        bigint max_deposit
        bigint min_monthly_rent
        bigint max_monthly_rent
        int sample_count
        varchar data_status
        varchar status
        varchar active_key UK
        datetime effective_from
        datetime effective_to
    }
    PRICE_STANDARD_CANDIDATE {
        bigint id PK
        bigint batch_job_id FK
        varchar sigungu_code
        varchar property_type
        varchar deal_type
        varchar calc_method
        int sample_count
        varchar data_status
        varchar calculated_month
        varchar status
    }
    PRICE_STANDARD_HISTORY {
        bigint id PK
        bigint price_standard_id FK
        varchar sigungu_code
        bigint prev_min_deposit
        bigint new_min_deposit
        bigint changed_by
        varchar change_reason
    }
    PRICE_STANDARD_BATCH_JOB {
        bigint id PK
        varchar job_month
        varchar status
        int total_request_count
        int success_count
        int fail_count
    }
    EXTERNAL_API_CALL_LOG {
        bigint id PK
        bigint batch_job_id FK
        varchar api_type
        varchar request_url
        boolean success
        int elapsed_time_ms
        datetime called_at
    }
```

## 관계 해설
- **USER — REALTOR**: 1:0..1. REALTOR 역할 사용자만 realtor 프로필을 가진다.
- **REALTOR — PROPERTY**: 1:N. 중개사가 매물을 등록.
- **PROPERTY — PROPERTY_VERIFICATION**: 1:N. submit 마다 검증 이력 1건(재요청 시 누적).
- **PROPERTY_VERIFICATION — REASON**: 1:N. 검증 사유(reasonType) 다건.
- **BATCH_JOB — CANDIDATE / CALL_LOG**: 1:N. 한 배치 실행이 여러 후보·호출로그 생성.
- **CANDIDATE → PRICE_STANDARD**: 후보 승인 시 신규 ACTIVE 1건 생성(`active_key` UNIQUE로 (지역,유형,거래유형)당 ACTIVE 1건).
- **PRICE_STANDARD — HISTORY**: 1:N. 기준 교체 시 이전/신규 값 이력.

## 무결성 포인트
- `PRICE_STANDARD.active_key` UNIQUE → 동일 (region, type, deal) ACTIVE 중복 방지 (plan D3).
- `REALTOR.user_id` UNIQUE → 사용자당 중개사 프로필 1개.
- `USER.email` UNIQUE.
- 검색 인덱스: `PROPERTY(status, sigungu_code, deal_type, property_type)`.
- `PRICE_STANDARD.active_key` = `sigungu_code:property_type:deal_type` (ACTIVE 일 때만).
