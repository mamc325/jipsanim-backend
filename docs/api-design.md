# API 설계 인덱스 (집사님)

전체 API 한눈에 보기. **정본(요청/응답 스키마)은 각 차수의 `specs/<차수>/contracts/` 이며, 이 문서는 요약 인덱스**다.
현재 **1차 MVP만 계약(contract) 확정**, 2차 이후는 범위만 표기(ROADMAP·원본 §16 기준). — Constitution VI(차수 분리)

- 공통 응답 래퍼: `{ success, data, error }` (`docs/tech-stack.md`, `ApiResponse`)
- 인증: `Authorization: Bearer <JWT>`
- 페이지네이션: `?page&size&sort`
- 정본: [`specs/001-price-verification-mvp/contracts/api-contract.md`](../specs/001-price-verification-mvp/contracts/api-contract.md)

## REST 컨벤션 (프로젝트 규칙)
- **CRUD**: 리소스 + 표준 메서드(`POST/GET/PATCH/DELETE`).
- **상태 전이(부수효과 있음)**: 액션을 **명사형 서브리소스**로 두고 **POST**. `.../approval`, `.../rejection`, `.../submission`, `.../cancellation`, `.../confirmation`. (동사 URL + PATCH 금지)
- **부수효과 없는 단순 플래그 갱신**: 리소스 `PATCH` (예: 알림 읽음 `PATCH /notifications/{id}`).
- **잡/작업 실행**: "잡 리소스 생성" → `POST` 컬렉션, `202` + 잡 리소스 반환, `GET`으로 폴링.
- **검색/필터**: 컬렉션에 쿼리 파라미터 (`GET /addresses?keyword=`).
- **본인 스코프 조회**: `/me/...` (역할별 뷰 허용).

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
| GET | `/api/addresses?keyword=` | REALTOR·ADMIN | 주소 표준화 → `bjdongCode`(10)/`sigunguCode`(5)/`regionName`. 실패 시 502 |

### 매물 (중개사)
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/properties` | REALTOR | DRAFT 생성 |
| PATCH | `/api/properties/{id}` | REALTOR(owner) | 수정(DRAFT/PENDING) |
| DELETE | `/api/properties/{id}` | REALTOR(owner) | soft delete |
| POST | `/api/properties/{id}/submission` | REALTOR(owner) | 검증 요청(자동 검증) → PENDING |

### 매물 조회·검색 (사용자)
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| GET | `/api/properties/{id}` | PUBLIC(ACTIVE)/owner·ADMIN(전체) | 상세 |
| GET | `/api/properties` | PUBLIC | QueryDSL 조건 검색(ACTIVE only) |

### 관리자 — 매물 검증
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| GET | `/api/admin/property-verifications?status&riskLevel` | ADMIN | 검증 대기 목록 |
| POST | `/api/admin/property-verifications/{id}/approval` | ADMIN | 승인 → ACTIVE |
| POST | `/api/admin/property-verifications/{id}/rejection` | ADMIN | 반려(body: reason) |

### 관리자 — 시세 기준 / 배치
| Method | Path | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/admin/price-standard-batch-jobs` | ADMIN | 수집 배치 잡 생성(=실행), 202 + 잡 |
| GET | `/api/admin/price-standard-batch-jobs` | ADMIN | 배치 실행 이력/폴링 |
| GET | `/api/admin/price-standard-candidates?status` | ADMIN | 시세 후보 목록 |
| POST | `/api/admin/price-standard-candidates/{id}/approval` | ADMIN | 후보 승인 → ACTIVE 교체(dataStatus 상속) |
| POST | `/api/admin/price-standard-candidates/{id}/rejection` | ADMIN | 후보 반려 |
| GET | `/api/admin/price-standards?status&sigunguCode` | ADMIN | 운영 기준 목록 |
| GET | `/api/admin/external-api-call-logs?apiType&success` | ADMIN | 외부 호출 이력(키 마스킹) |

---

## ⏳ 2차 이후 — 범위만 (계약 미작성, 컨벤션 잠정 적용)

착수 차수에서 `specs/<차수>/contracts/` 로 확정. 아래 경로는 위 컨벤션을 잠정 적용한 예상안.

### 2차 — 방문슬롯 / 대기열 / 예약 / Mock결제 (`specs/002-visit-reservation-queue`)
| Method | Path | 권한 |
| --- | --- | --- |
| POST | `/api/properties/{id}/visit-slots` | REALTOR |
| GET | `/api/properties/{id}/visit-slots` | PUBLIC |
| DELETE | `/api/visit-slots/{id}` | REALTOR |
| POST | `/api/visit-slots/{slotId}/waiting` | USER (대기열 진입=대기 엔트리 생성) |
| GET | `/api/visit-slots/{slotId}/waiting/me` | USER (내 순번) |
| POST | `/api/visit-slots/{slotId}/reservations` | USER (예약 생성) |
| GET | `/api/me/reservations` | USER·REALTOR (역할별 뷰) |
| POST | `/api/reservations/{id}/payments` | USER (결제 생성) |
| POST | `/api/payments/{id}/confirmation` | USER (결제 확정) |
| POST | `/api/payments/{id}/failure` | USER (결제 실패 처리) |

### 3차 — 예약취소 / 환불 / 정산
| Method | Path | 권한 |
| --- | --- | --- |
| POST | `/api/reservations/{id}/cancellation` | USER |
| POST | `/api/payments/{id}/refunds` | USER (환불 생성) |
| GET | `/api/me/settlements?month=` | REALTOR |
| GET | `/api/admin/settlements?startDate&endDate` | ADMIN |
| POST | `/api/admin/settlements/{id}/confirmation` | ADMIN |
| POST | `/api/admin/settlements/{id}/payout` | ADMIN (지급 처리) |

### 4차 — 알림 / Outbox
| Method | Path | 권한 |
| --- | --- | --- |
| GET | `/api/me/notifications` | USER |
| PATCH | `/api/notifications/{id}` | USER (읽음 플래그 — 부수효과 없어 PATCH) |
| POST | `/api/admin/outbox-events/{id}/retries` | ADMIN (재처리 시도 생성) |

### 미배정 — 신고
| Method | Path | 권한 |
| --- | --- | --- |
| POST | `/api/properties/{id}/reports` | USER |
| GET | `/api/admin/reports` | ADMIN |
| POST | `/api/admin/reports/{id}/resolution` | ADMIN |

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
