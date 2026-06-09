#!/bin/bash
# Jira Slack Bot — 전체 서비스 시작 스크립트
# 사용법: ./start.sh [build|restart|stop|status]

set -e
cd "$(dirname "$0")"

# --- 설정 ---
ENV_FILE=".env"
# 버전에 독립적으로 빌드 산출물(boot jar)을 자동 탐색한다. -plain.jar(라이브러리 jar)은 제외.
JAR_GLOB="build/libs/slackbot-server-*-SNAPSHOT.jar"
SPRING_LOG="/tmp/slackbot.log"
GOBOT_LOG="/tmp/gobot.log"

# --- 유틸 ---
print_status() {
    local name=$1 port=$2
    if lsof -i :"$port" >/dev/null 2>&1; then
        local pid=$(lsof -ti :"$port" | head -1)
        echo "  ✓ $name (port $port, PID $pid)"
    else
        echo "  ✗ $name (port $port, not running)"
    fi
}

stop_all() {
    echo "=== Stopping services ==="
    # Spring Boot (8080)
    local java_pid=$(lsof -ti :8080 2>/dev/null | head -1)
    if [ -n "$java_pid" ]; then
        kill "$java_pid" 2>/dev/null && echo "  Stopped Spring Boot (PID $java_pid)"
    fi
    # Go bot (3000)
    local go_pid=$(lsof -ti :3000 2>/dev/null | head -1)
    if [ -n "$go_pid" ]; then
        kill "$go_pid" 2>/dev/null && echo "  Stopped Go bot (PID $go_pid)"
    fi
    sleep 1
}

check_docker() {
    if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q 'jirabot-postgres'; then
        echo "=== Starting PostgreSQL ==="
        docker-compose up -d
        sleep 2
    fi
    echo "  ✓ PostgreSQL (port 5432)"
}

build_jar() {
    echo "=== Building Spring Boot ==="
    ./gradlew build -x test --quiet
    echo "  ✓ Build complete"
}

start_spring() {
    if lsof -i :8080 >/dev/null 2>&1; then
        echo "  Spring Boot already running, skipping"
        return
    fi
    echo "=== Starting Spring Boot ==="
    local jar
    jar=$(ls $JAR_GLOB 2>/dev/null | grep -v -- '-plain.jar' | head -1)
    if [ -z "$jar" ]; then
        echo "  ✗ boot jar not found ($JAR_GLOB) — run build first"
        return
    fi
    set -a && source "$ENV_FILE" && set +a
    nohup java -jar "$jar" > "$SPRING_LOG" 2>&1 &
    echo "  Started (PID $!, jar: $jar, log: $SPRING_LOG)"

    # health check (최대 15초 대기)
    for i in $(seq 1 15); do
        if curl -s http://localhost:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then
            echo "  ✓ Spring Boot healthy"
            return
        fi
        sleep 1
    done
    echo "  ⚠ Spring Boot health check timeout — check $SPRING_LOG"
}

start_gobot() {
    if lsof -i :3000 >/dev/null 2>&1; then
        echo "  Go bot already running, skipping"
        return
    fi
    echo "=== Starting Go bot ==="
    (cd bot && nohup go run . > "$GOBOT_LOG" 2>&1 &)
    sleep 2
    if lsof -i :3000 >/dev/null 2>&1; then
        echo "  ✓ Go bot started (log: $GOBOT_LOG)"
    else
        echo "  ⚠ Go bot failed to start — check $GOBOT_LOG"
    fi
}

show_status() {
    echo "=== Service Status ==="
    print_status "PostgreSQL" 5432
    print_status "Spring Boot" 8080
    print_status "Go bot" 3000
    # ngrok
    if pgrep -x ngrok >/dev/null 2>&1; then
        echo "  ✓ ngrok (running)"
    else
        echo "  ✗ ngrok (not running — start separately)"
    fi
}

# --- 메인 ---
case "${1:-start}" in
    start)
        check_docker
        build_jar
        start_spring
        start_gobot
        echo ""
        show_status
        echo ""
        echo "⚠ ngrok은 별도로 실행해주세요: ngrok http 3000"
        ;;
    restart)
        stop_all
        check_docker
        build_jar
        start_spring
        start_gobot
        echo ""
        show_status
        ;;
    stop)
        stop_all
        show_status
        ;;
    status)
        show_status
        ;;
    build)
        stop_all
        check_docker
        build_jar
        start_spring
        start_gobot
        echo ""
        show_status
        ;;
    *)
        echo "Usage: $0 [start|restart|stop|status|build]"
        echo "  start   — DB/서버/봇 시작 (이미 실행 중이면 skip)"
        echo "  restart — 전부 종료 후 재시작"
        echo "  stop    — 서버/봇 종료"
        echo "  status  — 현재 상태 확인"
        echo "  build   — 재빌드 후 재시작"
        exit 1
        ;;
esac
