# API 설계 인덱스 (집사님)

전체 API 한눈에 보기. **정본(요청/응답 스키마)은 각 차수의 `specs/<차수>/contracts/` 이며, 이 문서는 요약 인덱스**다.
현재 **1~4차 구현 완료**, 5차 이후는 범위만 표기(ROADMAP 기준). — Constitution VI(차수 분리)

- 공통 응답 래퍼: `{ success, data, error }` (`docs/tech-stack.md`, `ApiResponse`)
- 인증: `Authorization: Bearer <JWT>`
- 페이지네이션: `?page&size&sort`
- 정본: [`specs/001-price-verification-mvp/contracts/api-contract.md`](../specs/001-price-verification-mvp/contracts/api-contract.md)

## REST 컨벤션 (프로젝트 규칙)
- **CRUD**: 리소스 + 표준 메서드(`POST/GET/PATCH/DELETE`).
- **상태 전이(부수효과 있음)**: 액션을 **명사형 서브리소스**로 두고 **POST**. `.../approval`, `.../rejection`, `.../submission`, `.../cancellation`, `.../confirmation`. (동사 URL + PATCH 금지)
- **부수효과 없는 단순 플래그 갱신**: 리소스 `PATCH` (예: 알림 읽음 `PATCH /notifications/{id}`).
- **잡/작업 실행**: "잡 리소스 생성" → `POST` 컬렉션, `202` + 잡 리소스 반환, `GET`으로 폴링.
  - **예외(3차 정산 배치)**: `POST /admin/settlement-batch-jobs` 는 **동기 200** + 결과 카운트 반환(별도 job 엔티티 없음). 정본은 [`specs/003-refund-settlement/contracts/api-contract.md`](../specs/003-refund-settlement/contracts/api-contract.md).
- **검색/필터**: 컬렉션에 쿼리 파라미터 (`GET /addresses?keyword=`).
- **본인 스코프 조회**: `/me/...` (역할별 뷰 허용).

---

## ✅ 1차 MVP — 구현 완료 (19)

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

## ✅ 2차·3차·4차 — 구현 완료 / 5차 이후 예정

2·3·4차는 구현·테스트 완료. 5차 이후는 착수 차수에서 `specs/<차수>/contracts/` 로 확정.

### 2차 — 방문슬롯 / 대기열 / 예약 / Mock결제 (`specs/002-visit-reservation-queue`, ✅ **구현 완료**)
정본: `specs/002-visit-reservation-queue/contracts/api-contract.md`
| Method | Path | 권한 |
| --- | --- | --- |
| POST | `/api/properties/{id}/visit-slots` | REALTOR |
| GET | `/api/properties/{id}/visit-slots` | PUBLIC |
| DELETE | `/api/visit-slots/{id}` | REALTOR (→CLOSED) |
| POST | `/api/visit-slots/{slotId}/waiting` | USER (대기열 진입 + tryIssue) |
| GET | `/api/visit-slots/{slotId}/waiting/me` | USER (순번/예약권 + tryIssue) |
| POST | `/api/visit-slots/{slotId}/reservations` | USER (예약 생성 **+ Payment(READY) 동시 생성**) |
| GET | `/api/me/reservations` | USER |
| POST | `/api/payments/{id}/confirmation` | USER (결제 확정 → 예약 CONFIRMED, slot RESERVED) |
| POST | `/api/payments/{id}/failure` | USER (결제 실패 → 예약 EXPIRED, 슬롯 반환) |

> 결정 §6-3: 결제는 예약 생성 시 동시 생성 → **별도 `POST /reservations/{id}/payments` 없음.**

### 3차 — 예약취소 / 환불 / 정산 (`specs/003-refund-settlement`, ✅ **구현 완료**)
정본: `specs/003-refund-settlement/contracts/api-contract.md`
| Method | Path | 권한 |
| --- | --- | --- |
| POST | `/api/reservations/{id}/cancellation` | USER (취소 **+ 환불 내부 생성**, 슬롯 OPEN 재개방) |
| GET | `/api/me/settlements?month=` | REALTOR |
| POST | `/api/admin/settlement-batch-jobs` | ADMIN (월별 정산 배치) |
| GET | `/api/admin/settlements?month=&realtorId=` | ADMIN |
| POST | `/api/admin/settlements/{id}/confirmation` | ADMIN (PENDING→CONFIRMED) |
| POST | `/api/admin/settlements/{id}/payout` | ADMIN (CONFIRMED→PAID) |

> 결정 §2: 취소가 환불을 내부 생성 → **별도 `POST /payments/{id}/refunds` 없음.**

### 4차 — 알림 / Outbox (`specs/004-outbox-notification`, ✅ **구현 완료**)
정본: `specs/004-outbox-notification/contracts/api-contract.md`
| Method | Path | 권한 |
| --- | --- | --- |
| GET | `/api/me/notifications?unread=` | USER/REALTOR/ADMIN (본인 알림) |
| PATCH | `/api/notifications/{id}` | 본인 (읽음 플래그 — 부수효과 없어 PATCH) |
| GET | `/api/admin/outbox-events?status=` | ADMIN (Outbox 모니터링) |
| POST | `/api/admin/outbox-events/{id}/reprocess` | ADMIN (DEAD→PENDING 재처리) |

### 5차 — 전문검색 / Elasticsearch (`specs/005-search-elasticsearch`, ✅ **구현 완료**)
정본: `specs/005-search-elasticsearch/contracts/`
| Method | Path | 권한 |
| --- | --- | --- |
| GET | `/api/properties/search?q=&sigunguCode=&dealType=&propertyType=&minDeposit=&maxDeposit=&minRent=&maxRent=&minArea=&maxArea=&roomCount=&page=&size=` | PUBLIC |

- nori 형태소 전문검색. `q` 는 `title^3`·`nearStation^2`·`regionName^2`·`description` multi_match 부스팅, 나머지는 필터. `status=ACTIVE` 강제.
- 정렬: `q` 있으면 `_score→createdAt→propertyId`, 없으면 `createdAt→propertyId`(최신순). `track_total_hits=true`.
- 검증(400): `min<=max`, `page>=0`, `1<=size<=100`, `(page+1)*size<=10000`(deep pagination 차단).
- ES 장애 시 `SEARCH_UNAVAILABLE`(503). 기존 `GET /api/properties`(QueryDSL 조건 검색)와 **별도 엔드포인트로 공존**.
- 색인 동기화: 매물 승인/삭제/반려 시 4차 Outbox(`PROPERTY_INDEX`/`PROPERTY_UNINDEX`)로 DB↔ES 정합성 유지.

### 6차 — 인기 매물 / 조회수 (`specs/006-ops-performance`, ✅ **구현 완료**)
정본: `specs/006-ops-performance/contracts/`
| Method | Path | 권한 |
| --- | --- | --- |
| GET | `/api/properties/popular?limit=`(기본 10, 1~50) | PUBLIC |

- 트렌딩 랭킹 Top-N(Redis ZSET+일 감쇠). **cache-aside 단일 키**(`popular:list` TTL 60s), Redis 장애 시 DB `view_count` desc 폴백. ACTIVE 제외는 조회 시 DB 필터가 보장(권위).
- 기존 `GET /api/properties/{id}`(상세) 응답에 **`viewCount`** 필드 추가. 조회 시 조회수 집계(ACTIVE 공개표현·dedup·best-effort). 상세 cache-aside(역할별 읽기: anonymous·USER=cache-first, REALTOR·ADMIN=우회).
- 관측성: `GET /actuator/prometheus`(무인증 스크레이프, 외부 노출은 compose/프록시 차단).

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
| SEARCH_UNAVAILABLE (5차, ES 장애) | 503 |
| INTERNAL_ERROR | 500 |

> 구현 시 springdoc-openapi 가 코드에서 OpenAPI(`/swagger-ui.html`)를 자동 생성한다. 이 문서는 사람이 읽는 인덱스로 유지.
