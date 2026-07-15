# 6차: 앱 멀티스테이지 이미지. build(JDK21 + gradlew) → JRE21 런타임.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 레이어 캐시(소스 변경과 분리)
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

# 소스 빌드(테스트는 CI 에서 수행 — 이미지 빌드는 bootJar 만)
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
