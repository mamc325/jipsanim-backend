# Data Model: 실거래가 기반 시세 검증 MVP

MySQL 8. 금액은 원 단위 `BIGINT`(만원 환산 여부는 파싱 계층에서 정규화). 시각은 `DATETIME(6)`.
1차에서 생성하는 테이블만 포함한다. 예약/결제/정산/알림/Outbox 는 후속 차수.

## Enums
```
Role                : USER, REALTOR, ADMIN
PropertyType        : OFFICETEL (MVP), (APARTMENT, VILLA ... 후속)
DealType            : JEONSE, MONTHLY_RENT
PropertyStatus      : DRAFT, PENDING, ACTIVE, REJECTED, CLOSED, HIDDEN, DELETED
VerificationStatus  : PENDING, APPROVED, REJECTED, REVIEW_REQUIRED
RiskLevel           : LOW, MEDIUM, HIGH
ReasonType          : MISSING_REQUIRED_FIELD, MISSING_IMAGE, DESCRIPTION_TOO_SHORT,
                      PRICE_OUT_OF_RANGE, ADDRESS_REGION_MISMATCH, DUPLICATE_SUSPECTED,
                      INSUFFICIENT_STANDARD
PriceStandardStatus : ACTIVE, EXPIRED
CandidateStatus     : PENDING, APPROVED, REJECTED
DataStatus          : SUFFICIENT, INSUFFICIENT_DATA
BatchStatus         : RUNNING, SUCCESS, PARTIAL_FAILED, FAILED
ApiType             : ADDRESS, REAL_ESTATE_OFFICETEL_RENT
```

## 1. user
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| email | VARCHAR(255) | UNIQUE |
| password | VARCHAR(100) | BCrypt |
| nickname | VARCHAR(50) | |
| role | VARCHAR(20) | Role |
| created_at / updated_at | DATETIME(6) | |

## 2. realtor
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| user_id | BIGINT | FK user.id, UNIQUE |
| business_name | VARCHAR(100) | |
| phone | VARCHAR(30) | |
| created_at / updated_at | DATETIME(6) | |

## 3. property
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| realtor_id | BIGINT | FK realtor.id |
| title | VARCHAR(200) | |
| description | TEXT | |
| road_address | VARCHAR(255) | 표준 주소 |
| bjdong_code | VARCHAR(10) | 법정동코드 10자리 (정밀 위치) |
| sigungu_code | VARCHAR(5) | 시군구코드 5자리 (bjdong_code 앞 5자리, 시세 기준 매칭 키) |
| region_name | VARCHAR(100) | |
| near_station | VARCHAR(100) | nullable |
| property_type | VARCHAR(20) | PropertyType |
| deal_type | VARCHAR(20) | DealType |
| deposit | BIGINT | 원 |
| monthly_rent | BIGINT | nullable(JEONSE) |
| area | DECIMAL(7,2) | ㎡ |
| room_count | INT | |
| status | VARCHAR(20) | PropertyStatus, default DRAFT |
| verification_status | VARCHAR(20) | nullable until submit |
| risk_level | VARCHAR(10) | nullable until submit |
| created_at / updated_at | DATETIME(6) | |

Index: `(status, sigungu_code, deal_type, property_type)` 검색용, `(realtor_id)`.

## 4. property_image
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| property_id | BIGINT | FK property.id |
| image_url | VARCHAR(500) | |
| is_primary | BOOLEAN | 대표 이미지 |
| sort_order | INT | |
| created_at | DATETIME(6) | |

## 5. property_verification
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| property_id | BIGINT | FK property.id |
| requested_by | BIGINT | user.id (realtor) |
| reviewed_by | BIGINT | nullable, admin user.id |
| status | VARCHAR(20) | VerificationStatus |
| risk_level | VARCHAR(10) | RiskLevel |
| rejected_reason | VARCHAR(500) | nullable |
| reviewed_at | DATETIME(6) | nullable |
| created_at | DATETIME(6) | |

Index: `(status, risk_level)` 관리자 조회용, `(property_id)`.

## 6. property_verification_reason
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| verification_id | BIGINT | FK property_verification.id |
| reason_type | VARCHAR(40) | ReasonType |
| message | VARCHAR(500) | |
| created_at | DATETIME(6) | |

## 7. price_standard  (운영 기준)
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| sigungu_code | VARCHAR(5) | 시군구 5자리 (실거래가 수집 단위 = 기준 단위) |
| region_name | VARCHAR(100) | |
| property_type | VARCHAR(20) | |
| deal_type | VARCHAR(20) | |
| min_deposit / max_deposit | BIGINT | |
| min_monthly_rent / max_monthly_rent | BIGINT | nullable(JEONSE) |
| sample_count | INT | |
| data_status | VARCHAR(20) | DataStatus. 승인 시 후보값 상속. INSUFFICIENT_DATA 이면 검증에서 HIGH 판정 대신 REVIEW_REQUIRED (FR-042) |
| source | VARCHAR(50) | 예: MOLIT_OFFICETEL_RENT |
| status | VARCHAR(20) | PriceStandardStatus |
| effective_from | DATETIME(6) | |
| effective_to | DATETIME(6) | nullable(ACTIVE=null) |
| active_key | VARCHAR(64) | **생성 컬럼**: status='ACTIVE' → `sigungu_code:property_type:deal_type`, else NULL |
| created_at / updated_at | DATETIME(6) | |

- **UNIQUE(active_key)**: 동일 (시군구,유형,거래유형) ACTIVE 1건 보장 (plan D3). MVP 는 매매(salePrice) 제외.
- 소표본 후보도 승인 가능하되 `data_status=INSUFFICIENT_DATA` 로 표시되어 자동 위험 판정(HIGH)에는 사용하지 않는다. (422 하드 차단 대신 flag 방식 채택 — spec Clarifications 참조)

## 8. price_standard_candidate
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| sigungu_code | VARCHAR(5) | 시군구 5자리 |
| region_name | VARCHAR(100) | |
| property_type / deal_type | | |
| calc_min_deposit / calc_max_deposit | BIGINT | |
| calc_min_monthly_rent / calc_max_monthly_rent | BIGINT | nullable |
| calc_method | VARCHAR(20) | PERCENTILE / IQR |
| sample_count | INT | |
| data_status | VARCHAR(20) | DataStatus |
| source | VARCHAR(50) | |
| calculated_month | VARCHAR(7) | YYYY-MM |
| status | VARCHAR(20) | CandidateStatus, default PENDING |
| created_at | DATETIME(6) | |
| reviewed_at | DATETIME(6) | nullable |
| reviewed_by | BIGINT | nullable |
| batch_job_id | BIGINT | FK price_standard_batch_job.id |

Index: `(status, sigungu_code)`.

## 9. price_standard_history
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| price_standard_id | BIGINT | 신규 ACTIVE id |
| sigungu_code | VARCHAR(5) | |
| property_type / deal_type | | |
| prev_min_deposit / prev_max_deposit | BIGINT | nullable(최초) |
| prev_min_monthly_rent / prev_max_monthly_rent | BIGINT | nullable |
| new_min_deposit / new_max_deposit | BIGINT | |
| new_min_monthly_rent / new_max_monthly_rent | BIGINT | nullable |
| changed_by | BIGINT | admin user.id |
| change_reason | VARCHAR(255) | 예: CANDIDATE_APPROVED |
| created_at | DATETIME(6) | |

## 10. price_standard_batch_job
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| job_month | VARCHAR(7) | YYYY-MM |
| status | VARCHAR(20) | BatchStatus |
| total_request_count | INT | |
| success_count / fail_count | INT | |
| started_at / finished_at | DATETIME(6) | |
| error_message | VARCHAR(1000) | nullable |
| triggered_by | VARCHAR(20) | SCHEDULER / ADMIN |

## 11. external_api_call_log
| col | type | note |
| --- | --- | --- |
| id | BIGINT PK AI | |
| api_type | VARCHAR(40) | ApiType |
| request_url | VARCHAR(1000) | serviceKey(인증키) 제거·마스킹 후 저장 |
| request_params | VARCHAR(1000) | serviceKey 제거·마스킹 후 저장 |
| response_status | INT | nullable |
| success | BOOLEAN | |
| error_message | VARCHAR(1000) | nullable |
| elapsed_time_ms | INT | |
| batch_job_id | BIGINT | nullable, FK |
| called_at | DATETIME(6) | |

Index: `(api_type, called_at)`, `(batch_job_id)`.

## 관계 요약
- user 1—0..1 realtor
- realtor 1—N property 1—N property_image
- property 1—N property_verification 1—N property_verification_reason
- price_standard_batch_job 1—N price_standard_candidate, 1—N external_api_call_log
- candidate(APPROVED) → price_standard(ACTIVE) → price_standard_history

## 상태 전이 (매물/후보/기준)
```
Property        : DRAFT --submit--> PENDING --approve--> ACTIVE
                                    PENDING --reject---> REJECTED
                  ACTIVE --> CLOSED | HIDDEN ;  any --> DELETED(soft)
Verification    : (submit) PENDING | REVIEW_REQUIRED --admin--> APPROVED | REJECTED
Candidate       : PENDING --> APPROVED | REJECTED
PriceStandard   : (candidate approve) 기존 ACTIVE --> EXPIRED ; 신규 --> ACTIVE
```
(VisitSlot/Reservation 상태 전이표는 `specs/002-visit-reservation-queue/spec.md`)
