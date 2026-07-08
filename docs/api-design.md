# API 설계 인덱스 (집사님)

전체 API 한눈에 보기. **정본(요청/응답 스키마)은 각 차수의 `specs/<차수>/contracts/` 이며, 이 문서는 요약 인덱스**다.
현재 **1차 MVP만 계약(contract) 확정**, 2차 이후는 범위만 표기(ROADMAP·원본 §16 기준). — Constitution VI(차수 분리)

- 공통 응답 래퍼: `{ success, data, error }` (`docs/tech-stack.md`, `ApiResponse`)
- 인증: `Authorization: Bearer <JWT>`
- 페이지네이션: `?page&size&sort`
- 정본: [`specs/001-price-verification-mvp/contracts/api-contract.md`](../specs/001-price-verification-mvp/contracts/api-contract.md)

---

## ✅ 1차 MVP — 설계 완료 (19)

### 인증 / 사용자
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | PUBLIC | 회원가입(REALTOR 시 businessName/phone) |
| POST | `/api/auth/login` | PUBLIC | JWT access token 발급 |
| GET | `/api/me` | 인증 | 내 정보 |

### 주소 검색
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| GET | `/api/addresses/search?keyword=` | REALTOR·ADMIN | 주소 표준화 → `bjdongCode`(10)/`sigunguCode`(5)/`regionName`. 실패 시 502 |

### 매물 (중개사)
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/properties` | REALTOR | DRAFT 생성 |
| PATCH | `/api/properties/{id}` | REALTOR(owner) | 수정(DRAFT/PENDING) |
| DELETE | `/api/properties/{id}` | REALTOR(owner) | soft delete |
| POST | `/api/properties/{id}/submit` | REALTOR(owner) | 자동 검증 → PENDING + riskLevel/사유 |

### 매물 조회·검색 (사용자)
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| GET | `/api/properties/{id}` | PUBLIC(ACTIVE)/owner·ADMIN(전체) | 상세 |
| GET | `/api/properties` | PUBLIC | QueryDSL 조건 검색(ACTIVE only) |

### 관리자 — 매물 검증
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| GET | `/api/admin/property-verifications?status&riskLevel` | ADMIN | 검증 대기 목록 |
| PATCH | `/api/admin/property-verifications/{id}/approve` | ADMIN | 승인 → ACTIVE |
| PATCH | `/api/admin/property-verifications/{id}/reject` | ADMIN | 반려 |

### 관리자 — 시세 기준 / 배치
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/admin/price-standards/batch/run` | ADMIN | 실거래가 수집 배치 수동 실행 |
| GET | `/api/admin/price-standard-batch-jobs` | ADMIN | 배치 실행 이력 |
| GET | `/api/admin/price-standard-candidates?status` | ADMIN | 시세 후보 목록 |
| PATCH | `/api/admin/price-standard-candidates/{id}/approve` | ADMIN | 후보 승인 → ACTIVE 교체(dataStatus 상속) |
| PATCH | `/api/admin/price-standard-candidates/{id}/reject` | ADMIN | 후보 반려 |
| GET | `/api/admin/price-standards?status&sigunguCode` | ADMIN | 운영 기준 목록 |
| GET | `/api/admin/external-api-call-logs?apiType&success` | ADMIN | 외부 호출 이력(키 마스킹) |

---

## ⏳ 2차 이후 — 범위만 (계약 미작성)

착수 차수에서 `specs/<차수>/contracts/` 로 확정. 아래는 원본 §16 기준 예상 목록.

### 2차 — 방문슬롯 / 대기열 / 예약 / Mock결제 (`specs/002-visit-reservation-queue`, 상태전이·원자성은 spec에 있음)
| Method | Path | 권한 |
| --- | --- | --- |
| POST | `/api/properties/{id}/visit-slots` | REALTOR |
| GET | `/api/properties/{id}/visit-slots` | PUBLIC |
| DELETE | `/api/visit-slots/{id}` | REALTOR |
| POST | `/api/visit-slots/{slotId}/waiting` | USER |
| GET | `/api/visit-slots/{slotId}/waiting/me` | USER |
| POST | `/api/visit-slots/{slotId}/reservations` | USER |
| GET | `/api/me/reservations` | USER |
| GET | `/api/realtor/reservations` | REALTOR |
| POST | `/api/reservations/{id}/payments` | USER |
| PATCH | `/api/payments/{id}/confirm` | USER |
| PATCH | `/api/payments/{id}/fail` | USER |

### 3차 — 예약취소 / 환불 / 정산
| Method | Path | 권한 |
| --- | --- | --- |
| PATCH | `/api/reservations/{id}/cancel` | USER |
| POST | `/api/payments/{id}/refunds` | USER |
| GET | `/api/realtors/{id}/settlements?month=` | REALTOR |
| GET | `/api/admin/settlements?startDate&endDate` | ADMIN |
| PATCH | `/api/admin/settlements/{id}/confirm` | ADMIN |
| PATCH | `/api/admin/settlements/{id}/paid` | ADMIN |

### 4차 — 알림 / Outbox
| Method | Path | 권한 |
| --- | --- | --- |
| GET | `/api/me/notifications` | USER |
| PATCH | `/api/notifications/{id}/read` | USER |
| POST | `/api/admin/outbox-events/{id}/retry` | ADMIN |

### 미배정 — 신고
| Method | Path | 권한 |
| --- | --- | --- |
| POST | `/api/properties/{id}/reports` | USER |
| GET | `/api/admin/reports` | ADMIN |
| PATCH | `/api/admin/reports/{id}/resolve` | ADMIN |

---

## 에러 코드 (MVP)
| code | HTTP |
| --- | --- |
| VALIDATION_ERROR / EMAIL_DUPLICATED | 400 |
| INVALID_CREDENTIALS / UNAUTHORIZED | 401 |
| FORBIDDEN / NOT_OWNER | 403 |
| NOT_FOUND | 404 |
| INVALID_STATE / ALREADY_REVIEWED | 409 |
| EXTERNAL_ADDRESS_API_ERROR / EXTERNAL_REAL_ESTATE_API_ERROR | 502 |
| INTERNAL_ERROR | 500 |

> 구현 시 springdoc-openapi 가 코드에서 OpenAPI(`/swagger-ui.html`)를 자동 생성한다. 이 문서는 사람이 읽는 인덱스로 유지.
