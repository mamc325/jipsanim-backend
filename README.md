# 집사님 (Jipsanim) — 실거래가 기반 매물 검증 백엔드

외부 실거래가 API를 WebClient로 배치 수집해 시세 기준을 갱신하고, 이를 기반으로 매물 가격 이상치를 검증한다.
승인된 매물만 검색에 노출한다. (방문 예약 대기열·결제·정산·Outbox·Elasticsearch 는 후속 차수 — `ROADMAP.md`)

## 기술 스택
- Java 21, Spring Boot 3.5, Spring MVC, Spring Security(JWT), Spring Data JPA, QueryDSL
- WebClient (외부 API 경계 전용), MySQL 8, springdoc-openapi
- 테스트: JUnit5, Mockito, Testcontainers(MySQL), MockWebServer
- 상세 선정 근거: `docs/tech-stack.md`

## 아키텍처 요약
```
Spring MVC + JPA (핵심 트랜잭션)
 + WebClient 기반 외부 API 수집 (주소 / 국토부 실거래가)
 + Scheduler 기반 시세 수집 배치 (bounded concurrency)
 + 내부 ACTIVE 시세 기준으로만 매물 가격 검증 (외부 장애 격리)
```
- 설계 원칙: `.specify/memory/constitution.md`
- API 지도: `docs/api-design.md` · ERD: `docs/erd.md`

## 로컬 실행

### 1. 사전 준비
- JDK 21, Docker Desktop

### 2. DB 실행 (Docker Compose)
```bash
docker compose up -d          # jipsanim-mysql (MySQL 8, 3306, DB=jipsanim)
docker compose ps             # healthy 확인
```

### 3. 외부 API 키 설정 (커밋 금지)
`src/main/resources/application-local.yml` 생성 (`.gitignore`로 제외됨):
```yaml
external:
  address:
    api-key: "행안부 도로명주소 승인키"     # https://business.juso.go.kr
  molit:
    api-key: "국토부 실거래가 serviceKey"    # https://www.data.go.kr
```
키 발급 방법은 `docs/acceptance-mvp.md` 참고. (개발용은 자동 승인, 무료)

### 4. 앱 실행
```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```
- API 문서(Swagger): http://localhost:8080/swagger-ui.html

## 테스트
```bash
./gradlew test    # 통합 테스트는 Testcontainers 가 MySQL 컨테이너 자동 기동 (Docker 필요)
```

## MVP 기능 흐름
1. (중개사) 주소 검색으로 표준 주소·지역코드 확보 → 매물 DRAFT 등록
2. (중개사) 검증 요청 → 서버 자동 검증(필수정보·이미지·설명·주소정합·**가격 이상치**·중복)
3. 가격은 **내부 ACTIVE 시세 기준**과 비교. 기준 없음/표본부족 → HIGH 대신 REVIEW_REQUIRED
4. (관리자) 검증 결과 확인 후 승인 → 매물 ACTIVE
5. (사용자) 조건 검색으로 ACTIVE 매물 조회
6. (배치/관리자) 국토부 실거래가 수집 → 시세 후보 생성 → 승인 시 ACTIVE 기준 교체(이력 보존)

## 문서
| 문서 | 위치 |
| --- | --- |
| 프로젝트 원칙 | `.specify/memory/constitution.md` |
| 차수 로드맵 | `ROADMAP.md` |
| 기능 명세/계획 | `specs/001-price-verification-mvp/` |
| API 지도 / ERD | `docs/api-design.md`, `docs/erd.md` |
| 기술 선정(ADR) | `docs/tech-stack.md` |
| 브랜치/커밋 전략 | `docs/git-workflow.md` |
| 인수기준·키발급·시드 | `docs/acceptance-mvp.md` |
