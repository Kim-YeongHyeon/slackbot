# Spring Boot 서버 이미지 (멀티 스테이지: 소스에서 직접 빌드 → 고객은 JDK/Gradle 불필요)
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
# 의존성 레이어 캐시: 빌드 스크립트만 먼저 복사해 dependencies 를 분리 다운로드
COPY gradlew settings.gradle* build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:17-jre
WORKDIR /app

# Claude Code CLI — AI 분류/검색/브랜치 슬러그에 사용 (standalone 설치, node 불필요).
# 인증은 이미지에 굽지 않는다: 호스트에서 `claude login` 후 ~/.claude 를 볼륨 마운트 (docker-compose.yml 참고).
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL https://claude.ai/install.sh | bash \
    && apt-get clean && rm -rf /var/lib/apt/lists/*
ENV PATH="/root/.local/bin:${PATH}"

COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
# 프롬프트 skill 파일 — CLI 가 working dir 상대 경로(prompts/*.md)로 읽는다
COPY prompts prompts

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
