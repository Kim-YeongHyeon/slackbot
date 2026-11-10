#!/usr/bin/env bash
# JDK 워치독 — unattended-upgrades 가 openjdk 를 in-place 교체하면 실행 중인 JVM 은
# 서브프로세스(claude CLI)를 못 만든다 (jspawnhelper 버전 불일치, lessons L17).
# 탐지: JDK 가 교체되면 실행 중인 java 프로세스의 /proc/PID/exe 심링크가 "(deleted)" 로 표시된다.
# 조치: 실행 중이던 jar 를 그대로 재시작 (배포 스크립트와 동일한 setsid + .env source 패턴).
# 설치: crontab — */10 * * * * /home/ubuntu/slackbot/scripts/jdk-watchdog.sh >> /tmp/jdk-watchdog.log 2>&1
set -u

APP_DIR=/home/ubuntu/slackbot
# cron 의 PATH 에는 ~/.local/bin 이 없다. 봇은 이 PATH 를 물려받아 `claude` 를 찾으므로 여기서 넣는다
# (2026-11-10 실발생: 재시작된 봇의 모든 claude 호출이 "Cannot run program \"claude\"" 로 실패, lessons L25).
export PATH="/home/ubuntu/.local/bin:$PATH"
ts() { date '+%F %T'; }

# 봇 java 프로세스 찾기 (bash 래퍼 제외 — comm 이 java 인 것만)
PID=""
for p in $(pgrep -f 'slackbot-server-.*-SNAPSHOT\.jar' 2>/dev/null); do
    if [ "$(cat /proc/$p/comm 2>/dev/null)" = "java" ]; then PID=$p; break; fi
done

if [ -z "$PID" ]; then
    # 봇이 아예 안 떠 있음 — 워치독 범위 밖 (수동 배포 중일 수 있음). 기록만.
    echo "$(ts) bot java process not found — skip"
    exit 0
fi

EXE=$(readlink "/proc/$PID/exe" 2>/dev/null || echo "")
case "$EXE" in
    *"(deleted)"*) ;;                      # JDK 교체 감지 → 재시작 진행
    *) exit 0 ;;                           # 정상 — 조용히 종료 (10분마다 도는 기본 경로)
esac

echo "$(ts) JDK replaced under running JVM (pid=$PID exe='$EXE') — restarting bot"

# 지금 돌고 있던 jar 를 그대로 다시 띄운다. 가장 최근 jar 를 고르면 빌드만 하고 배포하지 않은 버전이 올라간다.
JAR=$(tr '\0' '\n' < "/proc/$PID/cmdline" 2>/dev/null | grep -m1 'slackbot-server-.*-SNAPSHOT\.jar$')
case "$JAR" in /*) ;; *) JAR="$APP_DIR/build/libs/$(basename "$JAR")" ;; esac
if [ ! -f "$JAR" ]; then
    echo "$(ts) ERROR: running jar '$JAR' not found — abort"
    exit 1
fi
if ! command -v claude > /dev/null 2>&1; then
    echo "$(ts) WARN: claude CLI not on PATH ($PATH) — LLM features will fail after restart"
fi

kill "$PID"
for i in $(seq 1 30); do
    ss -tln | grep -q :8080 || break
    sleep 1
done

setsid bash -c "set -a; source $APP_DIR/.env; set +a; exec java -jar '$JAR' > /tmp/slackbot.log 2>&1" < /dev/null &

for i in $(seq 1 40); do
    if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo "$(ts) restart OK — $(basename "$JAR") HEALTH UP (~$((i*2))s), claude=$(command -v claude || echo MISSING)"
        exit 0
    fi
    sleep 2
done
echo "$(ts) ERROR: restarted but health not UP within 80s — check /tmp/slackbot.log"
exit 1
