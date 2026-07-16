# 집사님(Jipsanim) API 명세서

> 부동산(오피스텔) 매물 검증 + 방문 예약/정산 + 알림 백엔드. 실제 구현 기준 명세.

## 📌 총 API 개수 (42개)

| 도메인 | API 개수 |
| --- | --- |
| 인증/회원 | 3개 |
| 주소 검색 | 1개 |
| 매물 (중개사) | 5개 (등록·수정·검증요청·삭제·내 매물 목록) |
| 매물 조회 (사용자) | 4개 (조건검색·상세·전문검색·인기) |
| 방문 슬롯 | 3개 |
| 대기열 / 예약 / 결제 | 6개 |
| 예약 취소/환불 | 1개 |
| 정산 (중개사) | 1개 |
| 알림 | 2개 |
| 관리자 | 16개 |

---

## 📋 0. API 개요

### 기본 정보

| 항목 | 내용 |
| --- | --- |
| **Base URL** | `http://localhost:8080` (prefix `/api`) |
| **인증 방식** | **Access Token**: JWT Bearer Token (`Authorization` 헤더) — Refresh Token 미사용(MVP) |
| **Content-Type** | `application/json` |
| **Character Encoding** | UTF-8 |
| **API 문서** | Swagger UI `/swagger-ui.html`, OpenAPI `/v3/api-docs` |
| **역할(Role)** | `USER`(일반 사용자) / `REALTOR`(중개사) / `ADMIN`(관리자) |

### 서비스 개요 (도메인 플로우)

```
[중개사] 매물 등록(DRAFT) → 검증 요청(submission)
      → 국토부 실거래가 배치로 만든 "시세 기준"으로 가격 리스크 자동 검증
      → 관리자 승인(ACTIVE) → [사용자] 검색/조회
      → 방문 예약(Redis 대기열 + TTL 예약권 + Mock 결제) → 예약 확정
      → 취소/환불(24h 전) → [중개사] 월별 정산
      → 알림(Outbox Pattern 비동기)
```

---

### 1. REST 설계 원칙

#### 1️⃣ 리소스 중심 설계

- URL은 동사가 아닌 **명사**, 리소스는 **복수형**, 계층 구조로 관계 표현.

```
❌ 나쁜 예                       ✅ 좋은 예
POST /createProperty            POST /api/properties
GET  /getMyReservations         GET  /api/me/reservations
POST /approveCandidate          POST /api/admin/price-standard-candidates/{id}/approval
```

- `/api/properties/{id}/visit-slots`: 매물에 속한 방문 슬롯
- `/api/visit-slots/{id}/reservations`: 슬롯에 속한 예약
- `/api/reservations/{id}/cancellation`: 예약의 취소 액션

#### 2️⃣ HTTP Method 의미론적 사용

| Method | 예시 | 사용 이유 |
| --- | --- | --- |
| GET | `/api/properties` | 조회, 멱등성 |
| POST | `/api/properties` | 리소스 생성, 비멱등 |
| POST(액션) | `/api/payments/{id}/confirmation` | **상태 전이**를 명사형 서브리소스로 |
| PATCH | `/api/properties/{id}` | 부분 수정 |
| PATCH(플래그) | `/api/notifications/{id}` | 부수효과 없는 플래그(읽음) 갱신 |
| DELETE | `/api/properties/{id}` | 삭제(soft delete) |

#### 3️⃣ 상태 전이 = 명사형 서브리소스 POST (프로젝트 규칙)

부수효과가 있는 상태 전이는 동사 URL + PATCH 대신 **명사형 액션 서브리소스 + POST**로 표현한다.

```
POST /api/properties/{id}/submission              (매물 검증 요청)
POST /api/payments/{id}/confirmation              (결제 확정)
POST /api/payments/{id}/failure                   (결제 실패)
POST /api/reservations/{id}/cancellation          (예약 취소/환불)
POST /api/admin/price-standard-candidates/{id}/approval    (후보 승인)
POST /api/admin/property-verifications/{id}/rejection      (검증 반려)
POST /api/admin/settlements/{id}/confirmation     (정산 확정)
POST /api/admin/settlements/{id}/payout           (정산 지급)
POST /api/admin/outbox-events/{id}/reprocess      (DEAD 이벤트 재처리)
```

- 근거: 상태 전이는 "생성되는 사건"에 가깝고, 멱등/충돌(409) 규칙을 URL에서 분명히 표현할 수 있다.
- 예외(잡 실행): 배치는 **잡 리소스 생성** `POST .../*-batch-jobs`. 시세 배치는 `202`, 정산 배치는 예외적으로 **동기 200**.

#### 4️⃣ `/me` 패턴 — 본인 스코프 조회

```
GET /api/me                 (내 프로필)
GET /api/me/reservations    (내 예약)
GET /api/me/settlements     (내 정산 — REALTOR)
GET /api/me/notifications   (내 알림)
```

- 토큰에서 `userId`/역할을 추출 → IDOR 방지, URL 간결화.

---

### 2. 인증

- 로그인(`POST /api/auth/login`) 시 **JWT Access Token** 발급 → 이후 요청은 `Authorization: Bearer {accessToken}`.
- 인증 필터가 토큰을 검증해 `AuthUser(userId, role)`를 주입. Stateless(세션 없음).
- 권한: `@PreAuthorize("hasRole('...')")` + `/api/admin/**`는 SecurityConfig에서 `ADMIN` 강제.
- **공개(비인증) 경로**: `/api/auth/**`, `GET /api/properties`·`/api/properties/search`·`/api/properties/*`·`/api/properties/*/visit-slots`, Swagger, `/actuator/health`.

**JWT 페이로드 예시**

```json
{ "sub": "1", "role": "REALTOR", "exp": 1780000000 }
```

---

### 3. 공통 응답 형식

**모든 응답은 공통 래퍼로 감싼다.**

성공:
```json
{ "success": true, "data": { /* API별 데이터 */ }, "error": null }
```

실패:
```json
{ "success": false, "data": null, "error": { "code": "ERROR_CODE", "message": "사용자 메시지" } }
```

- `success`: 처리 성공 여부
- `data`: 성공 시 페이로드(없으면 `null`)
- `error.code`: `ErrorCode` enum 이름(대문자 스네이크), `error.message`: 표시용 메시지

---

### 4. HTTP Status Codes

| Status | 의미 | 사용 케이스 |
| --- | --- | --- |
| **200 OK** | 성공 | GET/PATCH/상태전이 성공 |
| **201 Created** | 생성 | 회원가입, 매물/슬롯/예약/대기열 생성 |
| **202 Accepted** | 접수(비동기) | 시세 기준 배치 잡 생성 |
| **400 Bad Request** | 잘못된 요청 | 파라미터/형식 오류 |
| **401 Unauthorized** | 인증 실패 | 토큰 없음/만료 |
| **403 Forbidden** | 권한 없음 | 역할 부족, 타인 리소스 접근 |
| **404 Not Found** | 리소스 없음 | 존재하지 않는 ID |
| **409 Conflict** | 중복/충돌 | 상태 불일치, 동시성 경쟁, 중복 대기/예약권 |
| **422 Unprocessable Entity** | 검증 실패 | 표본 부족 후보 승인 시도 |
| **500 / 502** | 서버/외부 오류 | 내부 오류, 외부 API 실패 |

---

### 5. 공통 에러 코드 (`ErrorCode`)

| Status | Error Code | 설명 |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | 요청 값 검증 실패 |
| 400 | `EMAIL_DUPLICATED` | 이미 사용 중인 이메일 |
| 401 | `INVALID_CREDENTIALS` | 이메일/비밀번호 불일치 |
| 401 | `UNAUTHORIZED` | 인증 필요 |
| 403 | `FORBIDDEN` | 접근 권한 없음 |
| 403 | `NOT_OWNER` | 리소스 소유자 아님 |
| 404 | `NOT_FOUND` | 리소스 없음 |
| 409 | `INVALID_STATE` | 현재 상태에서 허용되지 않는 작업 |
| 409 | `ALREADY_REVIEWED` | 이미 처리된 건 |
| 409 | `CONFLICT` | 동시 요청 경쟁으로 실패 |
| 409 | `ALREADY_WAITING` | 이미 대기열에 있음 |
| 409 | `ALREADY_GRANTED` | 이미 예약권 보유 |
| 422 | `INSUFFICIENT_DATA_APPROVAL_BLOCKED` | 표본 부족 후보 승인 불가 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류 |
| 502 | `EXTERNAL_ADDRESS_API_ERROR` | 주소 조회 서비스 실패 |
| 502 | `EXTERNAL_REAL_ESTATE_API_ERROR` | 실거래가 조회 서비스 실패 |
| 503 | `SEARCH_UNAVAILABLE` | Elasticsearch 장애로 전문검색 일시 중단(5차) |

---

### 6. 페이지네이션 전략

**Spring Data `Pageable` 기반 Offset 페이지네이션**을 사용한다.

**요청 파라미터**: `page`(0-based, 기본 0), `size`(기본 20), `sort`(예 `createdAt,desc`)

**응답**(`data`가 Spring `Page` 직렬화):

```json
{
  "success": true,
  "data": {
    "content": [ /* 항목들 */ ],
    "totalElements": 42,
    "totalPages": 3,
    "number": 0,
    "size": 20,
    "first": true,
    "last": false,
    "numberOfElements": 20,
    "empty": false
  },
  "error": null
}
```

- 페이지 적용 대상: 매물 검색, 관리자 목록(검증/시세 후보/시세 기준/배치잡/정산/Outbox/외부 API 로그), 알림.
- 미적용(단건/전체): 인증, 방문 슬롯 목록, 내 예약 목록, 대기열 상태 등.

---

### 7. 상태 전이 다이어그램

**매물(Property) + 검증(Verification)**
```
DRAFT ──submission──▶ PENDING ──관리자 승인──▶ ACTIVE
                         │                       │
                         └──관리자 반려──▶ REJECTED   └──softDelete──▶ DELETED
검증(Verification): PENDING / APPROVED / REJECTED / REVIEW_REQUIRED(표본 부족·리스크)
```

**방문 슬롯 / 예약 / 결제 (2·3차)**
```
VisitSlot   : OPEN ──결제 확정──▶ RESERVED ──취소──▶ OPEN(재개방) / CLOSED / EXPIRED
Reservation : PENDING_PAYMENT ──결제 확정──▶ CONFIRMED ──취소(24h 전)──▶ CANCELLED
                     └──결제 실패/만료──▶ EXPIRED
Payment     : READY ──확정──▶ PAID ──환불──▶ REFUNDED / ──실패──▶ FAILED
예약권(Redis): 대기열 진입 → (선두) TTL 토큰 발급 → 결제 확정 시 소진 / 만료 시 다음 대기자
```

**정산(Settlement, 3차)**
```
PENDING ──관리자 확정──▶ CONFIRMED ──지급──▶ PAID
음수 정산 → carry_over_out(다음 달 이월)
```

**Outbox 이벤트 (4차)**
```
PENDING ──Worker 선점──▶ PROCESSING ──발행 성공──▶ PUBLISHED
                            └─실패(재시도<6)─▶ PENDING(지수 백오프)
                            └─실패(재시도=6)─▶ DEAD ──관리자 재처리──▶ PENDING
```

---

### 8. 알림 타입 (`NotificationType`)

| type | 발생 시점 | 수신자 |
| --- | --- | --- |
| `VISIT_RESERVATION_CONFIRMED` | 결제 확정 | 예약자 |
| `VISIT_RESERVATION_CANCELLED` | 예약 취소 | 예약자 |
| `REFUND_COMPLETED` | 환불 완료 | 예약자 |
| `SETTLEMENT_PAID` | 정산 지급 | 중개사 |
| `PROPERTY_APPROVED` | 매물 승인 | 중개사 |
| `PROPERTY_REJECTED` | 매물 반려 | 중개사 |
| `WAITING_QUEUE_INVITATION_GRANTED` | 예약권 발급(best-effort) | 발급 대상자 |

> 알림은 도메인 트랜잭션과 같은 커밋에 `OutboxEvent`로 적재되고, 폴링 Worker가 비동기로 발행한다(Outbox Pattern).

---

## 🔐 1. 인증/회원 (Auth & Users)

### 1.1 회원가입

```
POST /api/auth/signup
```

- **설계 근거**
    - 이메일/비밀번호 기반 로컬 회원가입. 역할(`role`)을 가입 시 지정(USER/REALTOR/ADMIN).
    - REALTOR 가입 시 `businessName`, `phone`으로 중개사 프로필을 함께 생성.
- **설계 결정 사항**
    - **POST 사용**: 새 사용자 리소스 생성(비멱등).
    - **비밀번호**: BCrypt 해시 저장. 이메일 UNIQUE.
    - **검증**: `@Email`, 비밀번호 8~100자, 닉네임 ≤50자.

**Request Body**

```json
{
  "email": "realtor@test.com",
  "password": "password123",
  "nickname": "강남중개",
  "role": "REALTOR",
  "businessName": "강남공인중개사",
  "phone": "010-1234-5678"
}
```

| 필드 | 타입 | 필수 | 제약사항 |
| --- | --- | --- | --- |
| email | string | ✅ | 이메일 형식, 중복 불가 |
| password | string | ✅ | 8~100자 |
| nickname | string | ✅ | ≤50자 |
| role | string | ✅ | USER, REALTOR, ADMIN |
| businessName | string | ❌ | REALTOR일 때 사용 |
| phone | string | ❌ | REALTOR일 때 사용 |

**Response 201**

```json
{ "success": true, "data": { "userId": 1, "role": "REALTOR" }, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 400 | EMAIL_DUPLICATED | 이미 사용 중인 이메일 |
| 400 | VALIDATION_ERROR | 요청 값 검증 실패 |

---

### 1.2 로그인

```
POST /api/auth/login
```

- **설계 근거**
    - 이메일/비밀번호 검증 후 **JWT Access Token** 발급. Stateless 인증.
- **설계 결정 사항**
    - Refresh Token 미사용(MVP) — `expiresIn`(초) 만료 시 재로그인.
    - 비밀번호 불일치/미존재 모두 `INVALID_CREDENTIALS`(401)로 통일(계정 존재 여부 노출 방지).

**Request Body**

```json
{ "email": "realtor@test.com", "password": "password123" }
```

**Response 200**

```json
{
  "success": true,
  "data": { "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "role": "REALTOR", "expiresIn": 3600 },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 401 | INVALID_CREDENTIALS | 이메일 또는 비밀번호 불일치 |

---

### 1.3 내 프로필 조회

```
GET /api/me
```

- **설계 근거**: `/me` 패턴으로 토큰의 `userId` 기반 본인 정보 조회(IDOR 방지).

**Headers**

| 헤더 | 값 | 필수 |
| --- | --- | --- |
| Authorization | Bearer {accessToken} | ✅ |

**Response 200**

```json
{
  "success": true,
  "data": { "userId": 1, "email": "realtor@test.com", "nickname": "강남중개", "role": "REALTOR" },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 인증 토큰 없음/만료 |

---

## 📍 2. 주소 검색

### 2.1 도로명 주소 검색

```
GET /api/addresses?keyword={keyword}&page=0&size=10
```

- **설계 근거**
    - 매물 등록 시 정확한 주소/법정동코드 확보를 위해 **행정안전부 주소 API(juso.go.kr)**를 프록시.
    - 외부 API를 도메인에서 격리(외부 호출 경계 전용 WebClient). 응답을 우리 스키마로 정규화.
- **설계 결정 사항**
    - **권한**: 매물을 다루는 REALTOR/ADMIN만 호출 가능.
    - **페이지**: 0-based `page`를 juso의 1-based로 변환해 호출.
    - 외부 실패 시 `EXTERNAL_ADDRESS_API_ERROR`(502).

**Headers**: `Authorization: Bearer {accessToken}` (REALTOR/ADMIN)

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| keyword | string | ✅ | - | 검색어(도로명/건물명 등) |
| page | number | ❌ | 0 | 0-based 페이지 |
| size | number | ❌ | 10 | 페이지 크기 |

**Response 200**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "roadAddress": "서울특별시 강남구 테헤란로 123",
        "bjdongCode": "1168010100",
        "sigunguCode": "11680",
        "regionName": "강남구",
        "zipNo": "06234"
      }
    ],
    "totalCount": 1
  },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN | USER 등 권한 없는 호출 |
| 502 | EXTERNAL_ADDRESS_API_ERROR | 주소 API 호출 실패 |

---

## 🏢 3. 매물 (중개사 · REALTOR)

> 매물 생명주기: `DRAFT`(초안) → `submission`(검증 요청) → `PENDING` → 관리자 승인 `ACTIVE` / 반려 `REJECTED`.
> 수정/삭제는 `DRAFT`/`PENDING` 상태에서만 가능(ACTIVE 매물 수정 불가).

### 3.1 매물 등록

```
POST /api/properties
```

- **설계 근거**
    - 중개사가 오피스텔 매물을 **초안(DRAFT)**으로 등록. 이후 검증 요청으로 승인 절차 진입.
    - 주소의 `bjdongCode`에서 `sigunguCode`를 파생 → 시세 기준(시군구 단위) 매칭에 사용.
- **설계 결정 사항**
    - **POST 사용**: 새 매물 리소스 생성. 초기 상태 `DRAFT`.
    - **검증**: 제목 ≤200자, 법정동코드 5~10자, 보증금 `PositiveOrZero` 필수.

**Headers**: `Authorization: Bearer {accessToken}` (REALTOR)

**Request Body**

```json
{
  "title": "강남역 5분 풀옵션 오피스텔",
  "description": "즉시 입주 가능, 채광 우수",
  "roadAddress": "서울특별시 강남구 테헤란로 123",
  "bjdongCode": "1168010100",
  "regionName": "강남구",
  "nearStation": "강남역",
  "propertyType": "OFFICETEL",
  "dealType": "MONTHLY_RENT",
  "deposit": 10000000,
  "monthlyRent": 700000,
  "area": 33.0,
  "roomCount": 1,
  "images": [ { "imageUrl": "https://cdn.example.com/1.jpg", "isPrimary": true } ]
}
```

| 필드 | 타입 | 필수 | 제약사항 |
| --- | --- | --- | --- |
| title | string | ✅ | ≤200자 |
| description | string | ❌ | |
| roadAddress | string | ✅ | 도로명 주소 |
| bjdongCode | string | ✅ | 5~10자 법정동코드 |
| regionName | string | ❌ | 지역명 |
| nearStation | string | ❌ | 인근 역 |
| propertyType | string | ✅ | OFFICETEL |
| dealType | string | ✅ | JEONSE, MONTHLY_RENT |
| deposit | number | ✅ | ≥0 |
| monthlyRent | number | ❌ | ≥0 |
| area | number | ❌ | 전용면적(㎡) |
| roomCount | number | ❌ | 방 개수 |
| images | array | ❌ | `{ imageUrl, isPrimary }` |

**Response 201**

```json
{ "success": true, "data": { "propertyId": 12, "status": "DRAFT" }, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 요청 값 검증 실패 |
| 403 | FORBIDDEN | REALTOR 아님 |

---

### 3.2 매물 수정

```
PATCH /api/properties/{propertyId}
```

- **설계 근거**: 부분 수정이므로 PATCH. `null`이 아닌 필드만 반영.
- **설계 결정 사항**: **`DRAFT`/`PENDING` 상태에서만 수정 가능**(ACTIVE 매물 수정 시 `INVALID_STATE`). 소유자만.

**Headers**: `Authorization: Bearer {accessToken}` (REALTOR)

**Request Body** (모든 필드 optional — 3.1과 유사, `images`는 전체 교체)

**Response 200**

```json
{ "success": true, "data": { "propertyId": 12, "status": "DRAFT" }, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN / NOT_OWNER | 다른 중개사의 매물 |
| 404 | NOT_FOUND | 매물 없음 |
| 409 | INVALID_STATE | DRAFT/PENDING 아님(수정 불가) |

---

### 3.3 매물 검증 요청 (상태 전이)

```
POST /api/properties/{propertyId}/submission
```

- **설계 근거**
    - 등록한 매물의 가격이 시세 대비 적정한지 **자동 검증 엔진**을 태우고 관리자 승인 대기(`PENDING`)로 보낸다.
    - 검증 엔진: 매물의 시군구/거래유형에 해당하는 **시세 기준(price standard)** 범위와 비교 → 리스크 산정.
- **설계 결정 사항**
    - **명사형 액션 서브리소스 POST**(상태 전이). `DRAFT → PENDING`.
    - 시세 기준 **표본 부족(INSUFFICIENT_DATA)** 이거나 범위 밖이면 `verificationStatus=REVIEW_REQUIRED`로 관리자 판단 유도.

**Headers**: `Authorization: Bearer {accessToken}` (REALTOR)

**Response 200**

```json
{
  "success": true,
  "data": {
    "propertyId": 12,
    "status": "PENDING",
    "verificationStatus": "REVIEW_REQUIRED",
    "riskLevel": "MEDIUM",
    "reasons": [
      { "reasonType": "PRICE_OUT_OF_RANGE", "message": "월세가 시세 상한을 초과합니다." }
    ]
  },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN / NOT_OWNER | 다른 중개사의 매물 |
| 404 | NOT_FOUND | 매물 없음 |
| 409 | INVALID_STATE | DRAFT 아님(이미 제출/승인 등) |

---

### 3.4 매물 삭제

```
DELETE /api/properties/{propertyId}
```

- **설계 근거**: soft delete(`DELETED`). 소유자만.

**Headers**: `Authorization: Bearer {accessToken}` (REALTOR)

**Response 200**

```json
{ "success": true, "data": null, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN / NOT_OWNER | 다른 중개사의 매물 |
| 404 | NOT_FOUND | 매물 없음 |

---

### 3.5 내 매물 목록 (중개사)

```
GET /api/me/properties?status=&page=0&size=20
```

- **설계 근거**: 공개 검색(`GET /api/properties`)은 ACTIVE 만 반환하고 realtorId 필터가 없어, 중개사가 자기 **DRAFT/PENDING/REJECTED** 매물을 볼 수단이 없었음. `/me` 패턴으로 본인 전 상태 매물 조회. DTO projection.
- **Headers**: `Authorization: Bearer {accessToken}` (REALTOR)
- **Query**: `status`(optional, `PropertyStatus`), `page`/`size`. DELETED 는 항상 제외.

**Response 200** (`data` = Page of `MyPropertyResponse`)

```json
{
  "success": true,
  "data": {
    "content": [
      { "propertyId": 12, "title": "역삼 오피스텔", "regionName": "서울특별시 강남구 역삼동",
        "status": "PENDING", "verificationStatus": "REVIEW_REQUIRED", "riskLevel": "MEDIUM",
        "dealType": "MONTHLY_RENT", "deposit": 10000000, "monthlyRent": 700000,
        "area": 33.0, "roomCount": 1, "createdAt": "2026-07-16T10:00:00" }
    ],
    "totalElements": 1, "totalPages": 1, "number": 0, "size": 20, "first": true, "last": true
  },
  "error": null
}
```

**Error Responses**: 403(REALTOR 아님)

---

## 🔎 4. 매물 조회 (사용자 · 공개)

### 4.1 매물 검색 (조건 검색)

```
GET /api/properties?regionName=&sigunguCode=&dealType=&propertyType=&minDeposit=&maxDeposit=&minRent=&maxRent=&minArea=&maxArea=&roomCount=&page=0&size=20
```

- **설계 근거**
    - 승인된(ACTIVE) 매물을 다양한 조건으로 검색. **QueryDSL 동적 쿼리**로 null 조건은 무시.
    - 공개 API(비로그인 조회 허용).
- **설계 결정 사항**: 모든 필터 optional. Offset 페이지네이션(`page`/`size`/`sort`).

**Query Parameters (모두 optional)**

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| regionName | string | 지역명 |
| sigunguCode | string | 시군구 코드 |
| dealType | string | JEONSE, MONTHLY_RENT |
| propertyType | string | OFFICETEL |
| minDeposit / maxDeposit | number | 보증금 범위 |
| minRent / maxRent | number | 월세 범위 |
| minArea / maxArea | number | 면적 범위 |
| roomCount | number | 방 개수 |
| page / size / sort | - | 페이지네이션 |

**Response 200** (`data` = Spring Page)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "propertyId": 12, "title": "강남역 5분 풀옵션 오피스텔", "regionName": "강남구",
        "dealType": "MONTHLY_RENT", "deposit": 10000000, "monthlyRent": 700000,
        "area": 33.0, "roomCount": 1, "primaryImageUrl": "https://cdn.example.com/1.jpg"
      }
    ],
    "totalElements": 1, "totalPages": 1, "number": 0, "size": 20, "first": true, "last": true
  },
  "error": null
}
```

---

### 4.2 매물 상세 조회

```
GET /api/properties/{propertyId}
```

- **설계 근거**: 매물 상세 + 이미지 + 검증 상태/리스크를 한 번에 조회. 공개 API.
- **6차 부수효과**: ACTIVE 공개표현 조회 시 **조회수 집계**(dedup·best-effort) + **상세 cache-aside**(역할별 읽기: anonymous·USER=cache-first, REALTOR·ADMIN=우회→DB). 응답에 **`viewCount`** 추가.
- **`priceStandard`(nullable)**: (sigungu·매물유형·거래유형) ACTIVE 시세 기준(min/max 보증금·월세, sampleCount, dataStatus). 상세 화면 "시세 대비" 계산용. 기준 없으면 null. 상세 캐시에 포함(시세 기준 변경 빈도 낮아 TTL 300s stale 허용).

**Response 200**

```json
{
  "success": true,
  "data": {
    "propertyId": 12, "realtorId": 3, "title": "강남역 5분 풀옵션 오피스텔",
    "description": "즉시 입주 가능", "roadAddress": "서울특별시 강남구 테헤란로 123",
    "bjdongCode": "1168010100", "sigunguCode": "11680", "regionName": "강남구", "nearStation": "강남역",
    "propertyType": "OFFICETEL", "dealType": "MONTHLY_RENT",
    "deposit": 10000000, "monthlyRent": 700000, "area": 33.0, "roomCount": 1,
    "status": "ACTIVE", "verificationStatus": "APPROVED", "riskLevel": "LOW", "viewCount": 1532,
    "priceStandard": { "minDeposit": 8000000, "maxDeposit": 12000000, "minMonthlyRent": 600000,
      "maxMonthlyRent": 850000, "sampleCount": 42, "dataStatus": "SUFFICIENT" },
    "images": [ { "imageUrl": "https://cdn.example.com/1.jpg", "primary": true, "sortOrder": 0 } ]
  },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 404 | NOT_FOUND | 매물 없음 |

---

### 4.3 매물 전문검색 (Elasticsearch + nori · 5차)

```
GET /api/properties/search?q=&sigunguCode=&dealType=&propertyType=&minDeposit=&maxDeposit=&minRent=&maxRent=&minArea=&maxArea=&roomCount=&page=0&size=20
```

- **설계 근거**
    - `q` 한글 형태소(nori) 전문검색 + 필드 부스팅으로 **관련도 순** 결과. 조건 검색(4.1, QueryDSL)과 **별도 엔드포인트로 공존** — 4.1 은 정확 필터, 4.3 은 자연어 검색.
    - 색인은 4차 Outbox 재사용(매물 승인 시 `PROPERTY_INDEX`, ACTIVE 이탈 시 `PROPERTY_UNINDEX`) → DB↔ES 정합성을 커밋 원자성 + 이중 멱등으로 보장. 검색은 항상 `status=ACTIVE`만 노출.
    - 공개 API(비로그인 조회 허용).
- **설계 결정 사항**
    - `q` multi_match 부스팅: `title^3` · `nearStation^2` · `regionName^2` · `description`. 나머지 파라미터는 필터(스코어 미반영).
    - analyzer `korean_nori`: `nori_tokenizer(decompound_mode=mixed)` + `korean_pos_filter`(조사/접미사 등 stoptags 제거). 복합어는 부분어로도 매칭(예: `전력` → `한국전력공사`).
    - 정렬 tie-breaker: `q` 있으면 `_score → createdAt desc → propertyId desc`, `q` 없으면 `createdAt desc → propertyId desc`(최신순). `track_total_hits=true`로 정확한 총건수.
    - 검증(400): `min<=max`(보증금/월세/면적), `page>=0`, `1<=size<=100`, `(page+1)*size<=10000`(deep pagination 차단).
    - ES 장애 시 `SEARCH_UNAVAILABLE`(503)로 격리 — 조건 검색(4.1) 경로에 무영향.

**Query Parameters (모두 optional)**

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| q | string | 전문검색어(형태소 분석·부스팅). 없으면 필터만 적용·최신순 |
| sigunguCode | string | 시군구 코드(term 필터) |
| dealType | string | JEONSE, MONTHLY_RENT |
| propertyType | string | OFFICETEL |
| minDeposit / maxDeposit | number | 보증금 범위 |
| minRent / maxRent | number | 월세 범위 |
| minArea / maxArea | number | 면적 범위 |
| roomCount | number | 방 개수 |
| page / size | - | 페이지네이션(`size` 1~100, `(page+1)*size<=10000`) |

**Response 200** (`data` = Spring Page, `content` 은 4.1 과 동일한 `PropertySummaryResponse`)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "propertyId": 12, "title": "강남역 5분 풀옵션 오피스텔", "regionName": "강남구",
        "dealType": "MONTHLY_RENT", "deposit": 10000000, "monthlyRent": 700000,
        "area": 33.0, "roomCount": 1, "primaryImageUrl": "https://cdn.example.com/1.jpg"
      }
    ],
    "totalElements": 1, "totalPages": 1, "number": 0, "size": 20, "first": true, "last": true
  },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | `min>max`, `size` 범위 초과, deep pagination |
| 503 | SEARCH_UNAVAILABLE | Elasticsearch 장애(검색만 일시 중단) |

---

### 4.4 인기 매물 (Redis 트렌딩 랭킹 · 6차)

```
GET /api/properties/popular?limit=10
```

- **설계 근거**
    - 조회수 기반 **트렌딩 Top-N**(Redis Sorted Set `property:popular`, 조회 시 `ZINCRBY`, **일 1회 감쇠**로 최근 인기 반영). 공개 API.
    - **cache-aside 단일 키**(`popular:list`, Top-50, TTL 60s). miss 시 `ZREVRANGE` over-fetch → **DB `status=ACTIVE` 필터**(제외 권위) → ZSET 순서 복원 → 캐시. Redis 장애 시 DB `view_count` desc 폴백(degrade).
- **설계 결정 사항**
    - `viewCount`(생애 누적, DB)와 트렌딩 score(Redis, 감쇠)는 **의도적 분리**. score 자체는 응답 미노출.
    - ACTIVE 이탈 매물 제외는 **조회 시 DB 필터가 보장** — ZSET stale member(ZREM 실패/재유입)는 감쇠/필터로 자연 정리. `popular:list` cache hit은 evict 실패 시 최대 60s stale 허용(정책).

**Query Parameters**

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| limit | number | 상위 개수. 기본 10, 1~50 (초과 시 400) |

**Response 200** (`data` = `List<PopularPropertyResponse>`)

```json
{
  "success": true,
  "data": [
    { "propertyId": 12, "title": "강남역 5분 풀옵션 오피스텔", "regionName": "강남구",
      "dealType": "MONTHLY_RENT", "deposit": 10000000, "monthlyRent": 700000,
      "area": 33.0, "roomCount": 1, "primaryImageUrl": "https://cdn.example.com/1.jpg", "viewCount": 1532 }
  ],
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | `limit` 범위(1~50) 위반 |

---

## 📅 5. 방문 슬롯 (Visit Slots)

> 슬롯당 방문 예약 1명 확정 구조. 슬롯 상태 `OPEN / RESERVED / CLOSED / EXPIRED`.

### 5.1 방문 슬롯 생성 (중개사)

```
POST /api/properties/{propertyId}/visit-slots
```

- **설계 근거**: 중개사가 자기 매물의 방문 가능 시간대를 슬롯으로 개설. 초기 `OPEN`.
- **설계 결정 사항**: 소유자만, `(property, startTime)` UNIQUE로 시간 중복 방지.

**Headers**: `Authorization: Bearer {accessToken}` (REALTOR)

**Request Body**

```json
{ "startTime": "2026-08-01T14:00:00", "endTime": "2026-08-01T14:30:00" }
```

**Response 201**

```json
{
  "success": true,
  "data": { "visitSlotId": 5, "startTime": "2026-08-01T14:00:00", "endTime": "2026-08-01T14:30:00", "status": "OPEN" },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN / NOT_OWNER | 다른 중개사의 매물 |
| 409 | CONFLICT | 시간 중복 슬롯 |

---

### 5.2 방문 슬롯 목록 조회 (공개)

```
GET /api/properties/{propertyId}/visit-slots
```

- **설계 근거**: 매물 상세 화면에서 예약 가능한 슬롯을 노출. 공개 API.

**Response 200**

```json
{
  "success": true,
  "data": [
    { "visitSlotId": 5, "startTime": "2026-08-01T14:00:00", "endTime": "2026-08-01T14:30:00", "status": "OPEN" }
  ],
  "error": null
}
```

---

### 5.3 방문 슬롯 마감 (중개사)

```
DELETE /api/visit-slots/{slotId}
```

- **설계 근거**: 더 이상 예약받지 않을 슬롯을 `CLOSED`로 마감. `OPEN`일 때만 조건부 전환(경합 방지).

**Headers**: `Authorization: Bearer {accessToken}` (REALTOR)

**Response 200**

```json
{ "success": true, "data": null, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN / NOT_OWNER | 다른 중개사의 슬롯 |
| 404 | NOT_FOUND | 슬롯 없음 |

---

## 🎟️ 6. 대기열 / 예약 / 결제 (Visit Reservation)

> **핵심 동시성 설계**: 인기 슬롯에 몰린 요청을 **Redis Sorted Set 대기열**로 줄 세우고, **Lua Script로 원자적으로 TTL 예약권(토큰)을 슬롯당 1명에게만** 발급한다. 예약권 보유자만 결제 확정 가능. 확정 시 슬롯당 1건만 성사되도록 앱 관리 UNIQUE 키로 보장.

### 6.1 대기열 진입 (예약권 시도)

```
POST /api/visit-slots/{slotId}/waiting
```

- **설계 근거**
    - 여러 사용자가 동시에 같은 슬롯을 예약하려 할 때, 순번 정합성과 중복 예약을 보장하기 위해 대기열을 둔다.
    - 진입 즉시 슬롯이 비어 있으면(선두) **예약권(TTL 토큰)** 을 원자적으로 발급.
- **설계 결정 사항**
    - **POST 사용**: 대기열 엔트리 생성. 진입과 동시에 발급 시도(`tryIssue`).
    - **멱등/충돌**: 이미 대기 중이면 `ALREADY_WAITING`, 이미 예약권 보유면 `ALREADY_GRANTED`.
    - `tokenGranted=true`면 곧바로 예약 생성으로 진행 가능.

**Headers**: `Authorization: Bearer {accessToken}` (USER)

**Response 201**

```json
{ "success": true, "data": { "slotId": 5, "position": 0, "tokenGranted": true }, "error": null }
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| position | number | 대기 순번(0 = 예약권 보유/선두) |
| tokenGranted | boolean | 예약권 발급 여부 |

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 409 | ALREADY_WAITING | 이미 대기열에 있음 |
| 409 | ALREADY_GRANTED | 이미 예약권 보유 |
| 409 | INVALID_STATE | 대기 가능한(OPEN) 슬롯 아님 |
| 404 | NOT_FOUND | 슬롯 없음 |

---

### 6.2 내 대기 상태 조회

```
GET /api/visit-slots/{slotId}/waiting/me
```

- **설계 근거**: 클라이언트가 순번/예약권 발급 여부를 폴링. 조회 시에도 발급 조건을 만족하면 예약권 발급 시도(`tryIssue`).
- **설계 결정 사항**: 토큰 보유 시 잔여 TTL(초)을 함께 반환.

**Headers**: `Authorization: Bearer {accessToken}` (USER)

**Response 200**

```json
{
  "success": true,
  "data": { "slotId": 5, "position": 0, "tokenGranted": true, "tokenExpiresInSeconds": 287 },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 404 | NOT_FOUND | 대기열/슬롯에 없음 |

---

### 6.3 예약 생성 (+ 결제 대기 생성)

```
POST /api/visit-slots/{slotId}/reservations
```

- **설계 근거**
    - 예약권 보유자가 예약을 생성. **예약 생성과 동시에 Mock 결제(READY)를 함께 생성**해 별도 결제 생성 API를 두지 않는다.
    - 예약은 `PENDING_PAYMENT` 상태로 TTL(예약권 만료 시간)이 걸린다.
- **설계 결정 사항**
    - **POST 사용**: 예약 리소스 생성(응답에 `paymentId` 포함).
    - **슬롯당 1건**: `active_reservation_key`(앱 관리 UNIQUE)로 활성 예약 1건만 허용.
    - 예약권 없으면 `FORBIDDEN`.

**Headers**: `Authorization: Bearer {accessToken}` (USER)

**Response 201**

```json
{
  "success": true,
  "data": { "reservationId": 11, "paymentId": 21, "status": "PENDING_PAYMENT", "amount": 10000, "expiresInSeconds": 300 },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN | 예약권 없음 |
| 409 | CONFLICT | 슬롯당 활성 예약 경쟁에서 밀림 |
| 404 | NOT_FOUND | 슬롯 없음 |

---

### 6.4 내 예약 목록

```
GET /api/me/reservations
```

- **설계 근거**: 사용자가 자신의 예약 이력을 조회. 예약 카드 표시용으로 **매물명·지역·방문 시간**을 함께 내려줌(Property·VisitSlot 조인).

**Headers**: `Authorization: Bearer {accessToken}` (USER)

**Response 200**

```json
{
  "success": true,
  "data": [
    {
      "reservationId": 11, "propertyId": 12, "visitSlotId": 5, "status": "CONFIRMED",
      "amount": 10000, "reservedAt": "2026-07-14T10:00:00", "confirmedAt": "2026-07-14T10:01:00",
      "propertyTitle": "역삼 센트럴 오피스텔", "regionName": "서울특별시 강남구 역삼동",
      "slotStartTime": "2026-07-16T10:00:00", "slotEndTime": "2026-07-16T10:30:00"
    }
  ],
  "error": null
}
```

---

### 6.5 결제 확정 (상태 전이)

```
POST /api/payments/{paymentId}/confirmation
```

- **설계 근거**
    - Mock 결제를 확정해 예약을 성사시킨다. 예약권 검증 + 슬롯 점유를 원자적으로 처리.
    - 락 순서 `Payment → Reservation → VisitSlot`로 데드락/경합 방지.
- **설계 결정 사항**
    - **명사형 액션 POST**(상태 전이). `Payment: READY→PAID`, `Reservation: →CONFIRMED`, `VisitSlot: OPEN→RESERVED`.
    - **멱등**: 이미 확정(PAID)이면 현재 상태 반환(200).
    - 확정 시 예약 확정 알림 이벤트 적재(Outbox). 커밋 후 예약권/큐 정리.

**Headers**: `Authorization: Bearer {accessToken}` (USER)

**Response 200**

```json
{
  "success": true,
  "data": { "paymentId": 21, "reservationId": 11, "paymentStatus": "PAID", "reservationStatus": "CONFIRMED", "visitSlotStatus": "RESERVED" },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN | 소유자 아님 / 예약권 없음 |
| 409 | INVALID_STATE | 확정 대상 아님(만료/이미 처리 등) 또는 이미 예약된 슬롯 |
| 404 | NOT_FOUND | 결제/예약/슬롯 없음 |

---

### 6.6 결제 실패 (상태 전이)

```
POST /api/payments/{paymentId}/failure
```

- **설계 근거**: 결제 실패를 확정해 예약을 만료 처리하고 예약권을 회수(큐 유지).
- **설계 결정 사항**: `Payment: →FAILED`, `Reservation: →EXPIRED`. **멱등**(이미 FAILED면 200). 확정(PAID)·환불(REFUNDED) 결제는 실패 처리 불가(409).

**Headers**: `Authorization: Bearer {accessToken}` (USER)

**Response 200**

```json
{ "success": true, "data": { "paymentId": 21, "paymentStatus": "FAILED", "reservationStatus": "EXPIRED" }, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN | 소유자 아님 |
| 409 | INVALID_STATE | 확정/환불된 결제는 실패 처리 불가 |
| 404 | NOT_FOUND | 결제 없음 |

---

## ↩️ 7. 예약 취소 / 환불

### 7.1 예약 취소 (+ 환불 + 슬롯 재개방)

```
POST /api/reservations/{reservationId}/cancellation
```

- **설계 근거**
    - 방문 24시간 전까지 취소 시 **전액 환불(Mock)** 하고 슬롯을 다시 `OPEN`으로 재개방해 다른 사용자가 예약 가능하게 한다.
    - 취소가 환불을 내부 생성하므로 별도 `/refunds` API를 두지 않는다.
- **설계 결정 사항**
    - **명사형 액션 POST**. **①잠금(Payment→Reservation→VisitSlot 전부) → ②검증(소유자/CONFIRMED/24h) → ③변경** 순서.
    - `Payment: PAID→REFUNDED`, `Reservation: →CANCELLED`, `VisitSlot: RESERVED→OPEN`, Refund 생성.
    - **멱등**: 이미 CANCELLED면 현재 상태 반환. **중복 환불 방지**: `refund.payment_id` UNIQUE → 경쟁 시 409.
    - 취소/환불 알림 이벤트 적재(Outbox).

**Headers**: `Authorization: Bearer {accessToken}` (USER)

**Response 200**

```json
{
  "success": true,
  "data": { "reservationId": 11, "reservationStatus": "CANCELLED", "paymentStatus": "REFUNDED", "refundAmount": 10000, "visitSlotStatus": "OPEN" },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN | 본인 예약 아님 |
| 409 | INVALID_STATE | CONFIRMED 아님 / 방문 24시간 이내 |
| 409 | CONFLICT | 중복 환불 |
| 404 | NOT_FOUND | 예약/결제 없음 |

---

## 💰 8. 정산 (중개사 · REALTOR)

### 8.1 내 정산 조회

```
GET /api/me/settlements?month=2026-07
```

- **설계 근거**: 중개사가 자신의 월별 정산 내역(결제 합/환불 합/수수료/지급액/이월)을 조회. `userId → Realtor` 매핑 후 조회.
- **설계 결정 사항**: `month`(YYYY-MM) 미지정 시 전체. 최신월 순.

**Headers**: `Authorization: Bearer {accessToken}` (REALTOR)

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| month | string | ❌ | 정산 월(YYYY-MM) |

**Response 200**

```json
{
  "success": true,
  "data": [
    {
      "settlementId": 3, "realtorId": 5, "realtorName": "역삼 센트럴 공인중개사", "settlementMonth": "2026-07",
      "totalPaymentAmount": 500000, "totalRefundAmount": 50000, "netAmount": 450000,
      "platformFee": 90000, "carryOverIn": 0, "carryOverOut": 0, "payoutAmount": 360000, "status": "CONFIRMED"
    }
  ],
  "error": null
}
```

> **정산 계산식**: `gross = 결제합 − 환불합 − 전월이월`, `수수료 = gross>0 ? floor(gross×0.2) : 0`, `지급액 = max(0, gross − 수수료)`, `이월 = max(0, −gross)`.

---

## 🔔 9. 알림 (Notifications)

### 9.1 내 알림 목록

```
GET /api/me/notifications?unread=false&page=0&size=20
```

- **설계 근거**: 본인(`recipientUserId`) 알림을 조회. `unread=true`면 미읽음만.

**Headers**: `Authorization: Bearer {accessToken}` (모든 인증 역할)

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| unread | boolean | ❌ | false | true면 미읽음만 |
| page / size / sort | - | ❌ | 0 / 20 | 페이지네이션 |

**Response 200** (`data` = Spring Page)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "notificationId": 7, "type": "VISIT_RESERVATION_CONFIRMED",
        "title": "예약이 확정되었습니다", "message": "방문 예약이 확정되었습니다.",
        "isRead": false, "createdAt": "2026-07-14T10:00:00"
      }
    ],
    "totalElements": 1, "totalPages": 1, "number": 0, "size": 20
  },
  "error": null
}
```

---

### 9.2 알림 읽음 처리 (플래그 갱신)

```
PATCH /api/notifications/{notificationId}
```

- **설계 근거**: 부수효과 없는 플래그(읽음) 갱신 → PATCH. 본인 알림만.

**Headers**: `Authorization: Bearer {accessToken}`

**Response 200**

```json
{
  "success": true,
  "data": { "notificationId": 7, "type": "VISIT_RESERVATION_CONFIRMED", "title": "예약이 확정되었습니다", "message": "...", "isRead": true, "createdAt": "2026-07-14T10:00:00" },
  "error": null
}
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 403 | FORBIDDEN | 본인 알림 아님 |
| 404 | NOT_FOUND | 알림 없음 |

---

## 🛠️ 10. 관리자 (Admin)

> `/api/admin/**` 는 `ADMIN` 역할만 접근 가능(SecurityConfig 강제). 그 외 역할은 403.

### 10.1 매물 검증 목록

```
GET /api/admin/property-verifications?status=&riskLevel=&page=0&size=20
```

- **설계 근거**: 검증 요청된 매물을 상태/리스크로 필터링해 심사 대기열을 확인. 화면 표시용으로 **매물명·지역·등록가·시세 기준**을 병합(propertyId 배치 조회 + 시세 기준 sigunguCode IN 배치 조회, N+1 금지). `reasons` 의 `PRICE_OUT_OF_RANGE` 로 프론트가 "가격 이상치" 뱃지 판정.

**Query Parameters (모두 optional)**

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| status | string | PENDING, APPROVED, REJECTED, REVIEW_REQUIRED |
| riskLevel | string | LOW, MEDIUM, HIGH 등 |
| page / size / sort | - | 페이지네이션 |

**Response 200** (`data` = Page of)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "verificationId": 8, "propertyId": 12, "status": "REVIEW_REQUIRED", "riskLevel": "MEDIUM",
        "reasons": ["PRICE_OUT_OF_RANGE"], "requestedAt": "2026-07-14T09:00:00",
        "propertyTitle": "역삼 오피스텔", "regionName": "서울특별시 강남구 역삼동",
        "dealType": "MONTHLY_RENT", "deposit": 10000000, "monthlyRent": 900000,
        "priceStandard": { "minDeposit": 8000000, "maxDeposit": 12000000, "minMonthlyRent": 600000,
          "maxMonthlyRent": 850000, "sampleCount": 42, "dataStatus": "SUFFICIENT" }
      }
    ],
    "totalElements": 1, "totalPages": 1, "number": 0, "size": 20
  },
  "error": null
}
```

---

### 10.2 매물 검증 승인 (상태 전이)

```
POST /api/admin/property-verifications/{verificationId}/approval
```

- **설계 근거**: 심사 통과 → 매물 `ACTIVE` 전이. 승인 알림 이벤트 적재(Outbox).
- **설계 결정 사항**: 명사형 액션 POST. 이미 처리된 건은 멱등/충돌 처리.

**Response 200**

```json
{ "success": true, "data": { "verificationId": 8, "propertyId": 12, "propertyStatus": "ACTIVE", "verificationStatus": "APPROVED" }, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 404 | NOT_FOUND | 검증 없음 |
| 409 | ALREADY_REVIEWED / INVALID_STATE | 이미 처리됨 |

---

### 10.3 매물 검증 반려 (상태 전이)

```
POST /api/admin/property-verifications/{verificationId}/rejection
```

- **설계 근거**: 심사 반려 → 매물 `REJECTED`. 사유(reason) 필수. 반려 알림 이벤트 적재.

**Request Body**

```json
{ "reason": "허위 매물 의심" }
```

**Response 200**

```json
{ "success": true, "data": { "verificationId": 8, "propertyId": 12, "propertyStatus": "REJECTED", "verificationStatus": "REJECTED" }, "error": null }
```

---

### 10.4 시세 기준 후보 목록

```
GET /api/admin/price-standard-candidates?status=&page=0&size=20
```

- **설계 근거**
    - 국토부 실거래가 배치가 시군구×거래유형 단위로 **시세 통계(p10~p90/IQR)** 를 계산해 만든 **승인 대기 후보**를 조회.
    - 표본 부족 시 `dataStatus=INSUFFICIENT_DATA`로 승인 게이팅.

**Query Parameters**

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| status | string | 후보 상태(PENDING/APPROVED/REJECTED 등) |
| page / size / sort | - | 페이지네이션 |

**Response 200** (`data` = Page of)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "candidateId": 30, "sigunguCode": "11680", "regionName": "강남구",
        "propertyType": "OFFICETEL", "dealType": "MONTHLY_RENT", "calcMethod": "IQR",
        "calcMinDeposit": 5000000, "calcMaxDeposit": 30000000,
        "calcMinMonthlyRent": 500000, "calcMaxMonthlyRent": 1200000,
        "sampleCount": 42, "dataStatus": "SUFFICIENT", "calculatedMonth": "2026-06", "status": "PENDING"
      }
    ],
    "totalElements": 1, "totalPages": 1, "number": 0, "size": 20
  },
  "error": null
}
```

---

### 10.5 시세 기준 후보 승인 (상태 전이)

```
POST /api/admin/price-standard-candidates/{candidateId}/approval
```

- **설계 근거**: 후보를 **운영 시세 기준(price standard)** 으로 승격. 활성 기준은 시군구×거래유형당 1건(app-managed UNIQUE).
- **설계 결정 사항**: **표본 부족 후보는 승인 불가**(`INSUFFICIENT_DATA_APPROVAL_BLOCKED`, 422).

**Response 200**

```json
{ "success": true, "data": { "candidateId": 30, "priceStandardId": 15, "status": "APPROVED", "activated": true, "dataStatus": "SUFFICIENT" }, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 404 | NOT_FOUND | 후보 없음 |
| 409 | ALREADY_REVIEWED / INVALID_STATE | 이미 처리됨 |
| 422 | INSUFFICIENT_DATA_APPROVAL_BLOCKED | 표본 부족 후보 |

---

### 10.6 시세 기준 후보 반려 (상태 전이)

```
POST /api/admin/price-standard-candidates/{candidateId}/rejection
```

- **설계 근거**: 부적절한 후보를 반려.

**Response 200**

```json
{ "success": true, "data": { "candidateId": 30, "priceStandardId": null, "status": "REJECTED", "activated": false, "dataStatus": "SUFFICIENT" }, "error": null }
```

---

### 10.7 운영 시세 기준 목록

```
GET /api/admin/price-standards?status=&sigunguCode=&page=0&size=20
```

- **설계 근거**: 현재 운영 중인(또는 이력) 시세 기준을 조회.

**Response 200** (`data` = Page of)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 15, "sigunguCode": "11680", "regionName": "강남구", "propertyType": "OFFICETEL",
        "dealType": "MONTHLY_RENT", "minDeposit": 5000000, "maxDeposit": 30000000,
        "minMonthlyRent": 500000, "maxMonthlyRent": 1200000, "sampleCount": 42,
        "dataStatus": "SUFFICIENT", "source": "MOLIT", "status": "ACTIVE",
        "effectiveFrom": "2026-07-01T00:00:00", "effectiveTo": null
      }
    ],
    "totalElements": 1, "totalPages": 1, "number": 0, "size": 20
  },
  "error": null
}
```

---

### 10.8 시세 기준 배치 실행 (잡 생성)

```
POST /api/admin/price-standard-batch-jobs
```

- **설계 근거**
    - 국토부 실거래가를 **비동기 병렬 수집**(WebClient bounded concurrency)해 시세 후보를 산출하는 배치 잡을 생성.
- **설계 결정 사항**: **잡 리소스 생성 → 202 Accepted** + 잡 리소스 반환(비동기). 후속은 `GET`으로 폴링.

**Request Body (optional)**

```json
{ "months": 3, "sigunguCodes": ["11680", "11650"] }
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| months | number | ❌ | 수집 개월 수 |
| sigunguCodes | array | ❌ | 대상 시군구(미지정 시 전체) |

**Response 202**

```json
{
  "success": true,
  "data": {
    "batchJobId": 7, "jobMonth": "2026-06", "status": "RUNNING",
    "totalRequestCount": 20, "successCount": 0, "failCount": 0,
    "startedAt": "2026-07-14T04:00:00", "finishedAt": null, "triggeredBy": "ADMIN"
  },
  "error": null
}
```

---

### 10.9 시세 기준 배치 잡 목록

```
GET /api/admin/price-standard-batch-jobs?page=0&size=20
```

- **설계 근거**: 배치 실행 이력/상태를 조회(폴링).

**Response 200** (`data` = Page of `BatchJobResponse`) — 필드는 10.8 응답과 동일.

---

### 10.10 정산 목록 (관리자)

```
GET /api/admin/settlements?month=2026-07&realtorId=5&page=0&size=20
```

- **설계 근거**: 전체 중개사 정산을 월/중개사로 필터링해 확정·지급 대상을 확인.

**Query Parameters (모두 optional)**: `month`(YYYY-MM), `realtorId`, `page/size/sort`

**Response 200** (`data` = Page of `SettlementResponse` — 8.1 필드 동일)

---

### 10.11 정산 확정 (상태 전이)

```
POST /api/admin/settlements/{settlementId}/confirmation
```

- **설계 근거**: 배치 생성된 정산(`PENDING`)을 확정(`CONFIRMED`).
- **설계 결정 사항**: 명사형 액션 POST. **멱등**: 이미 `CONFIRMED`/`PAID`면 현재 상태 반환(200).

**Response 200**

```json
{ "success": true, "data": { "settlementId": 3, "status": "CONFIRMED", "payoutAmount": 360000, "...": "..." }, "error": null }
```

---

### 10.12 정산 지급 (상태 전이)

```
POST /api/admin/settlements/{settlementId}/payout
```

- **설계 근거**: 확정된 정산을 지급(`PAID`). 지급 시 중개사에게 정산 지급 알림 이벤트 적재(Outbox).
- **설계 결정 사항**: `CONFIRMED→PAID`. **멱등**: 이미 `PAID`면 200. `PENDING`이면 `INVALID_STATE`(409).

**Response 200**

```json
{ "success": true, "data": { "settlementId": 3, "status": "PAID", "payoutAmount": 360000, "...": "..." }, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 409 | INVALID_STATE | 확정되지 않은 정산 지급 시도 |
| 404 | NOT_FOUND | 정산 없음 |

---

### 10.13 월별 정산 배치 실행 (동기)

```
POST /api/admin/settlement-batch-jobs
```

- **설계 근거**
    - 결제(paidAt)·환불(refundedAt)을 중개사×월로 집계해 정산(`PENDING`)을 생성. 음수 정산은 다음 달로 이월.
- **설계 결정 사항**
    - **예외적으로 동기 200**(별도 job 엔티티 없이 결과 카운트 반환).
    - **대상 realtor** = 당월 결제 ∪ 당월 환불 ∪ 전월 이월>0. 같은 realtor에 **이후 월 정산이 있으면 전체 409**(carry_over 연쇄 방지).

**Request Body (optional)**: `{ "month": "2026-07" }` (미지정 시 전월)

**Response 200**

```json
{ "success": true, "data": { "month": "2026-07", "createdCount": 12, "updatedCount": 0, "skippedCount": 1 }, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | month 형식 오류(YYYY-MM 아님) |
| 409 | INVALID_STATE | 이후 월 정산 존재(재계산 금지) |

---

### 10.14 Outbox 이벤트 목록 (모니터링)

```
GET /api/admin/outbox-events?status=DEAD&page=0&size=20
```

- **설계 근거**: 알림/색인 발행 파이프라인의 이벤트 상태를 모니터링(특히 `DEAD` 격리 건 확인).

**Query Parameters**: `status`(PENDING/PROCESSING/PUBLISHED/DEAD), `page/size/sort`

**Response 200** (`data` = Page of)

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "outboxEventId": 15, "aggregateType": "RESERVATION", "aggregateId": 11,
        "eventType": "VISIT_RESERVATION_CONFIRMED", "status": "DEAD", "attempts": 6,
        "nextRetryAt": "2026-07-14T16:00:00", "lastError": "sender timeout",
        "publishedAt": null, "createdAt": "2026-07-14T09:00:00"
      }
    ],
    "totalElements": 1, "totalPages": 1, "number": 0, "size": 20
  },
  "error": null
}
```

---

### 10.15 Outbox 이벤트 재처리 (상태 전이)

```
POST /api/admin/outbox-events/{outboxEventId}/reprocess
```

- **설계 근거**: 재시도 소진으로 `DEAD` 격리된 이벤트를 `PENDING`으로 되돌려 Worker가 재발행하게 한다.
- **설계 결정 사항**: `DEAD → PENDING`(attempts=0, 즉시 재발행). `DEAD`가 아니면 `INVALID_STATE`(409).

**Response 200**

```json
{ "success": true, "data": { "outboxEventId": 15, "status": "PENDING", "attempts": 0, "...": "..." }, "error": null }
```

**Error Responses**

| Status | Error Code | 설명 |
| --- | --- | --- |
| 409 | INVALID_STATE | DEAD 상태 아님 |
| 404 | NOT_FOUND | 이벤트 없음 |

---

### 10.16 외부 API 호출 로그 조회

```
GET /api/admin/external-api-call-logs?apiType=&success=&page=0&size=20
```

- **설계 근거**: 주소/실거래가 등 외부 API 호출 이력(키 마스킹 저장)을 조회해 장애/성공률을 모니터링.

**Query Parameters (모두 optional)**

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| apiType | string | ADDRESS, REAL_ESTATE_OFFICETEL_RENT |
| success | boolean | 성공/실패 필터 |
| page / size / sort | - | 페이지네이션 |

**Response 200**: `data` = Page of 외부 호출 로그(요청 요약·상태·소요시간·마스킹된 키 등).

---

## 부록. 도메인 enum 요약

| enum | 값 |
| --- | --- |
| Role | USER, REALTOR, ADMIN |
| PropertyType | OFFICETEL |
| DealType | JEONSE, MONTHLY_RENT |
| PropertyStatus | DRAFT, PENDING, ACTIVE, REJECTED, CLOSED, HIDDEN, DELETED |
| VerificationStatus | PENDING, APPROVED, REJECTED, REVIEW_REQUIRED |
| VisitSlotStatus | OPEN, RESERVED, CLOSED, EXPIRED |
| ReservationStatus | PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED |
| PaymentStatus | READY, PAID, FAILED, REFUNDED |
| SettlementStatus | PENDING, CONFIRMED, PAID |
| OutboxStatus | PENDING, PROCESSING, PUBLISHED, DEAD |
| NotificationType | (§8 알림 타입 참조) |
| ApiType | ADDRESS, REAL_ESTATE_OFFICETEL_RENT |

---

> 본 명세는 실제 구현(컨트롤러/DTO/ErrorCode) 기준으로 작성되었으며, Swagger UI(`/swagger-ui.html`)에서 실시간 스키마를 확인할 수 있습니다.
