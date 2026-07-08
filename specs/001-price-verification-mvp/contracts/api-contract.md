# API Contract: 실거래가 기반 시세 검증 MVP

- Base URL: `/api`
- 인증: `Authorization: Bearer <JWT>` (인증 필요 엔드포인트)
- 공통 응답 래퍼:
```json
{ "success": true, "data": { }, "error": null }
{ "success": false, "data": null, "error": { "code": "STRING", "message": "..." } }
```
- 페이지네이션: `?page=0&size=20&sort=createdAt,desc`, 응답 `data`에 `content/totalElements/totalPages/number`.
- 권한 표기: [USER] [REALTOR] [ADMIN] [PUBLIC]

### REST 컨벤션 (프로젝트 규칙)
- **CRUD**: 리소스 + 표준 메서드(`POST/GET/PATCH/DELETE`).
- **상태 전이(부수효과 있음)**: 액션을 **명사형 서브리소스**로 두고 **POST**. 예) `POST .../approval`, `.../rejection`, `.../submission`, `.../cancellation`. (동사 URL + PATCH 금지)
- **부수효과 없는 단순 플래그 갱신**: 리소스 `PATCH` (예: 알림 읽음).
- **잡/작업 실행**: "잡 리소스 생성"으로 모델링 → `POST` 컬렉션, `202` + 잡 리소스 반환, `GET`으로 폴링.
- **검색/필터**: 컬렉션에 쿼리 파라미터 (`GET /addresses?keyword=`).
- **본인 스코프 조회**: `/me/...`.

---

## 인증 / 사용자

### POST /api/auth/signup  [PUBLIC]
```json
// req
{ "email": "a@b.com", "password": "pw", "nickname": "닉", "role": "REALTOR",
  "businessName": "OO공인", "phone": "010-..." }  // businessName/phone: role=REALTOR일 때
// res 201
{ "userId": 1, "role": "REALTOR" }
```
- 400 `EMAIL_DUPLICATED`, 400 `VALIDATION_ERROR`.

### POST /api/auth/login  [PUBLIC]
```json
// req { "email": "a@b.com", "password": "pw" }
// res 200 { "accessToken": "jwt...", "role": "REALTOR", "expiresIn": 3600 }
```
- 401 `INVALID_CREDENTIALS`.

### GET /api/me  [USER|REALTOR|ADMIN]
```json
// res 200 { "userId": 1, "email": "a@b.com", "nickname": "닉", "role": "REALTOR" }
```

---

## 주소 검색

### GET /api/addresses?keyword=서울 강남구 역삼동&page=0&size=10  [REALTOR|ADMIN]
```json
// res 200
{ "content": [
    { "roadAddress": "서울특별시 강남구 테헤란로 …",
      "bjdongCode": "1168010100",   // 법정동코드 10자리 (정밀 위치)
      "sigunguCode": "11680",       // 시군구코드 5자리 (실거래가/시세 기준 키)
      "regionName": "서울특별시 강남구 역삼동" } ],
  "totalCount": 3 }
```
- 502 `EXTERNAL_ADDRESS_API_ERROR`: 주소 검색은 외부 주소 API에 직접 의존하는 요청이므로, 실패 시 502로 명확히 반환한다. (Constitution I의 "격리"는 외부 장애를 **매물 검증·검색 등 핵심 내부 요청**으로 전파하지 않는다는 의미이며, 외부 의존 요청 자체의 502 반환과 상충하지 않는다.) 호출 결과는 `ExternalApiCallLog` 기록.

---

## 매물 (중개사)

### POST /api/properties  [REALTOR]  → DRAFT 생성
```json
// req
{ "title":"역삼 오피스텔","description":"...(20자+)","roadAddress":"...","bjdongCode":"1168010100",
  "regionName":"...","nearStation":"강남역","propertyType":"OFFICETEL","dealType":"MONTHLY_RENT",
  // sigunguCode(5자리)는 서버가 bjdongCode 앞 5자리로 파생 저장
  "deposit":10000000,"monthlyRent":700000,"area":33.0,"roomCount":1,
  "images":[{"imageUrl":"https://...","isPrimary":true}] }
// res 201 { "propertyId": 10, "status": "DRAFT" }
```

### PATCH /api/properties/{propertyId}  [REALTOR, owner]
- 부분 수정. DRAFT/PENDING 에서만 허용. res 200 매물 요약. 403 `NOT_OWNER`.

### DELETE /api/properties/{propertyId}  [REALTOR, owner]  → soft delete (DELETED)

### POST /api/properties/{propertyId}/submission  [REALTOR, owner]  → 자동 검증
```json
// res 200
{ "propertyId":10, "status":"PENDING", "verificationStatus":"REVIEW_REQUIRED",
  "riskLevel":"HIGH",
  "reasons":[ {"reasonType":"PRICE_OUT_OF_RANGE","message":"월세가 기준 하한 미만"},
              {"reasonType":"MISSING_IMAGE","message":"대표 이미지 없음"} ] }
```
- 409 `INVALID_STATE`(DRAFT 아님).

### GET /api/properties/{propertyId}  [PUBLIC(ACTIVE) | owner/ADMIN(전체)]

---

## 매물 검색 (사용자, ACTIVE only)

### GET /api/properties  [PUBLIC]
Query: `regionName, sigunguCode, dealType, propertyType, minDeposit, maxDeposit, minRent, maxRent, minArea, maxArea, roomCount, sort, page, size`
```json
// res 200 (page)
{ "content":[ {"propertyId":10,"title":"...","regionName":"...","dealType":"MONTHLY_RENT",
   "deposit":10000000,"monthlyRent":700000,"area":33.0,"roomCount":1,"primaryImageUrl":"..."} ],
  "totalElements":42,"totalPages":3,"number":0 }
```
- ACTIVE 이외 상태는 결과에서 제외(FR-051).

---

## 관리자: 매물 검증

### GET /api/admin/property-verifications?status=REVIEW_REQUIRED&riskLevel=HIGH&page=0  [ADMIN]
```json
// res 200 (page) content[]:
{ "verificationId":5,"propertyId":10,"status":"REVIEW_REQUIRED","riskLevel":"HIGH",
  "reasons":["PRICE_OUT_OF_RANGE","MISSING_IMAGE"],"requestedAt":"2026-07-07T.." }
```

### POST /api/admin/property-verifications/{verificationId}/approval  [ADMIN]
```json
// res 200 { "verificationId":5,"propertyId":10,"propertyStatus":"ACTIVE","verificationStatus":"APPROVED" }
```
- 409 `ALREADY_REVIEWED` (멱등: 이미 처리된 건 재처리 금지).

### POST /api/admin/property-verifications/{verificationId}/rejection  [ADMIN]
```json
// req { "rejectedReason":"허위 가격 의심" }
// res 200 { "verificationStatus":"REJECTED","propertyStatus":"REJECTED" }
```

---

## 관리자: 시세 기준 배치 / 후보 / 기준

### POST /api/admin/price-standard-batch-jobs  [ADMIN]  → 배치 잡 생성(=실행)
```json
// req(optional) { "months": 3, "sigunguCodes": ["11680","11650"] }  // 시군구 5자리, 미지정 시 전체 대상
// res 202 Location: /api/admin/price-standard-batch-jobs/3
//         { "batchJobId": 3, "status": "RUNNING", "jobMonth":"2026-07" }
```
- 잡 실행 = 리소스 생성. 생성 후 `GET /api/admin/price-standard-batch-jobs/{id}` 로 진행 상태를 폴링.

### GET /api/admin/price-standard-batch-jobs?page=0  [ADMIN]
```json
// content[]:
{ "batchJobId":3,"jobMonth":"2026-07","status":"PARTIAL_FAILED",
  "totalRequestCount":25,"successCount":23,"failCount":2,
  "startedAt":"..","finishedAt":"..","triggeredBy":"ADMIN" }
```

### GET /api/admin/price-standard-candidates?status=PENDING&page=0  [ADMIN]
```json
// content[]:
{ "candidateId":7,"sigunguCode":"11680","regionName":"강남구",
  "propertyType":"OFFICETEL","dealType":"MONTHLY_RENT","calcMethod":"IQR",
  "calcMinDeposit":5000000,"calcMaxDeposit":50000000,
  "calcMinMonthlyRent":550000,"calcMaxMonthlyRent":1800000,
  "sampleCount":18,"dataStatus":"INSUFFICIENT_DATA","calculatedMonth":"2026-06","status":"PENDING" }
```

### POST /api/admin/price-standard-candidates/{candidateId}/approval  [ADMIN]
- 기존 ACTIVE→EXPIRED, 신규 ACTIVE, History 생성 (트랜잭션).
```json
// res 200 { "candidateId":7,"priceStandardId":42,"status":"APPROVED","activated":true,"dataStatus":"INSUFFICIENT_DATA" }
```
- 409 `ALREADY_REVIEWED`.
- 후보의 `dataStatus=INSUFFICIENT_DATA` 여도 승인은 **허용**하되, 생성되는 `PriceStandard` 가 `dataStatus=INSUFFICIENT_DATA` 를 상속한다. 이 기준으로 검증 시 가격 규칙은 HIGH 판정 대신 `REVIEW_REQUIRED` 로 처리한다(FR-042). 관리자 판단으로 소표본 기준을 반영할 수 있게 하되 자동 위험 판정에는 쓰지 않는다.

### POST /api/admin/price-standard-candidates/{candidateId}/rejection  [ADMIN]
```json
// req { "reason":"표본 부족" }  // res 200 { "candidateId":7,"status":"REJECTED" }
```

### GET /api/admin/price-standards?status=ACTIVE&sigunguCode=11680&page=0  [ADMIN]
```json
// content[]:
{ "id":42,"sigunguCode":"11680","regionName":"강남구","propertyType":"OFFICETEL","dealType":"MONTHLY_RENT",
  "minDeposit":5000000,"maxDeposit":50000000,"minMonthlyRent":550000,"maxMonthlyRent":1800000,
  "sampleCount":120,"dataStatus":"SUFFICIENT","source":"MOLIT_OFFICETEL_RENT","status":"ACTIVE",
  "effectiveFrom":"..","effectiveTo":null }
```

### GET /api/admin/external-api-call-logs?apiType=REAL_ESTATE_OFFICETEL_RENT&success=false&page=0  [ADMIN]
```json
// content[]: requestUrl/requestParams 는 serviceKey(인증키)를 제거·마스킹한 값으로 저장/응답한다.
{ "id":100,"apiType":"REAL_ESTATE_OFFICETEL_RENT","requestUrl":"...&serviceKey=***","responseStatus":null,
  "success":false,"errorMessage":"timeout","elapsedTimeMs":5000,"batchJobId":3,"calledAt":".." }
```

---

## 에러 코드 요약
| code | HTTP | 의미 |
| --- | --- | --- |
| VALIDATION_ERROR | 400 | 요청 검증 실패 |
| EMAIL_DUPLICATED | 400 | 이메일 중복 |
| INVALID_CREDENTIALS | 401 | 로그인 실패 |
| FORBIDDEN / NOT_OWNER | 403 | 권한/소유자 아님 |
| NOT_FOUND | 404 | 리소스 없음 |
| INVALID_STATE | 409 | 상태 전이 불가 |
| ALREADY_REVIEWED | 409 | 이미 처리(멱등) |
| EXTERNAL_ADDRESS_API_ERROR | 502 | 외부 주소 API 실패 |
| EXTERNAL_REAL_ESTATE_API_ERROR | 502 | 외부 실거래가 API 실패 |
