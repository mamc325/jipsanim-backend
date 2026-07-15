plugins {
	java
	id("org.springframework.boot") version "3.5.16"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.jipsanim"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

val queryDslVersion = "5.1.0"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	// WebClient(외부 API 호출 경계 전용). MVC 앱으로 구동됨.
	implementation("org.springframework.boot:spring-boot-starter-webflux")

	// Elasticsearch (5차: nori 한글 전문검색)
	implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

	// QueryDSL (jakarta) — 조건 검색
	implementation("com.querydsl:querydsl-jpa:${queryDslVersion}:jakarta")
	annotationProcessor("com.querydsl:querydsl-apt:${queryDslVersion}:jakarta")
	annotationProcessor("jakarta.annotation:jakarta.annotation-api")
	annotationProcessor("jakarta.persistence:jakarta.persistence-api")

	// 국토부 실거래가 XML 응답 파싱
	implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")

	// API 문서 자동 생성
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

	// JWT
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

	compileOnly("org.projectlombok:lombok")
	runtimeOnly("com.mysql:mysql-connector-j")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:mysql")
	testImplementation("org.testcontainers:elasticsearch")
	testImplementation("com.redis:testcontainers-redis:2.2.2")
	// 외부 API 클라이언트 테스트 (MockWebServer)
	testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.named<Test>("test") {
	// 일반 테스트에서는 부하테스트(@Tag("load")) 제외
	useJUnitPlatform { excludeTags("load") }
	// sweep 스케줄러는 테스트 중 비활성(결정적 검증). SweepService.sweep() 은 직접 호출해 검증.
	systemProperty("reservation.sweep-enabled", "false")
	// outbox 폴링 Worker 도 테스트 중 비활성. OutboxPoller.pollOnce() 를 직접 호출해 검증.
	systemProperty("outbox.worker-enabled", "false")
	// 6차 조회수 writeback 스케줄러도 테스트 중 비활성. ViewCountWriteback.flush() 를 직접 호출해 검증.
	systemProperty("viewcount.writeback-enabled", "false")
	// 6차 인기 랭킹 일 감쇠 스케줄러도 테스트 중 비활성. PopularRankingDecay.decay() 를 직접 호출해 검증.
	systemProperty("popular.decay-enabled", "false")
	// 5차 ES 는 공통 테스트에서 비활성(ES 컨테이너 없이 기동). ES 통합 테스트만 @DynamicPropertySource 로 true 오버라이드.
	systemProperty("search.elasticsearch.enabled", "false")
	systemProperty("spring.data.elasticsearch.repositories.enabled", "false")
	systemProperty("management.health.elasticsearch.enabled", "false")
}

// ./gradlew loadTest — 부하/벤치마크만 실행
tasks.register<Test>("loadTest") {
	useJUnitPlatform { includeTags("load") }
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	maxHeapSize = "1g"
	testLogging { showStandardStreams = true }
	outputs.upToDateWhen { false }
}

// QueryDSL Q타입 생성 위치를 build 디렉터리로 지정
val generatedDir = file("build/generated/sources/annotationProcessor/java/main")
tasks.withType<JavaCompile> {
	options.generatedSourceOutputDirectory.set(generatedDir)
}
sourceSets.main {
	java.srcDir(generatedDir)
}
tasks.named("clean") {
	doLast { generatedDir.deleteRecursively() }
}
