#!/bin/bash
# 로컬(Git Bash)에서 실행한다. 빌드는 로컬에서 하고 서버로는 산출물만 보낸다 —
# 서버에서 gradle을 돌리면 데몬이 메모리를 크게 먹어 앱과 경합한다.
set -euo pipefail

SERVER=ubuntu@158.179.177.14
KEY="${SSH_KEY:-$HOME/Desktop/오라클서버/ssh-key-2026-08-02.key}"

# 윈도우 시스템 환경변수가 Git Bash로 잘 안 넘어와서 여기서 잡는다.
# Git Bash는 /c/..., WSL은 /mnt/c/... 로 드라이브를 마운트한다. 윈도우는 java.exe다.
has_java() { [ -x "$1/bin/java" ] || [ -f "$1/bin/java.exe" ]; }

# WSL에서는 윈도우 JDK(java.exe)를 실행할 수 없다. /mnt/c 를 후보에 넣으면
# 경로 검사만 통과하고 gradlew가 "invalid directory"로 죽는다 — 아예 막는다.
if grep -qi microsoft /proc/version 2>/dev/null; then
  echo "WSL에서는 못 돌린다. Git Bash에서 실행하라:" >&2
  echo "  \"C:\\Program Files\\Git\\bin\\bash.exe\" deploy/deploy.sh" >&2
  exit 1
fi

for cand in \
  "${JAVA_HOME:-}" \
  "/c/Program Files/Java/jdk-17" \
  "/usr/lib/jvm/java-17-openjdk-amd64"
do
  [ -n "$cand" ] || continue
  if has_java "$cand"; then export JAVA_HOME="$cand"; break; fi
done

if [ -z "${JAVA_HOME:-}" ] || ! has_java "$JAVA_HOME"; then
  echo "JDK 17을 못 찾았다. JAVA_HOME을 주고 실행하라:" >&2
  echo "  JAVA_HOME='/c/Program Files/Java/jdk-17' ./deploy/deploy.sh" >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

cd "$(dirname "$0")/.."

echo "==> 백엔드 빌드"
./gradlew build -x test

echo "==> 프론트 빌드"
# Git Bash의 npm 래퍼가 node를 못 찾는 일이 잦다. 윈도우면 npm.cmd를 쓴다.
# SKIP_FRONT=1 로 주면 이미 빌드된 dist를 그대로 쓴다.
if [ "${SKIP_FRONT:-0}" = "1" ]; then
  echo "    건너뜀 (SKIP_FRONT=1)"
elif command -v npm.cmd >/dev/null 2>&1; then
  (cd frontend && npm.cmd run build)
else
  (cd frontend && npm run build)
fi

[ -f frontend/dist/index.html ] || { echo "frontend/dist 가 비었다. CMD에서 'cd frontend && npm run build' 후 SKIP_FRONT=1 로 재실행하라." >&2; exit 1; }

# Spring Boot는 실행 가능한 jar와 -plain.jar 두 개를 만든다. 앞의 것만 보낸다.
JAR=$(ls build/libs/*.jar | grep -v -- '-plain' | head -1)
echo "==> 전송: $JAR"

scp -i "$KEY" "$JAR" "$SERVER:/tmp/app.jar"
scp -i "$KEY" -r frontend/dist/. "$SERVER:/var/www/3am/dist/"

echo "==> 배치와 재시작"
# graceful shutdown(최대 20초)을 기다리므로 restart가 즉시 끝나지 않는다.
ssh -i "$KEY" "$SERVER" '
  sudo mv /tmp/app.jar /opt/3am/app.jar &&
  sudo chown threeam:threeam /opt/3am/app.jar &&
  sudo systemctl restart 3am &&
  sleep 3 &&
  sudo systemctl is-active 3am
'

echo "==> 로그 (Ctrl+C로 빠져나와도 서버는 계속 돈다)"
ssh -i "$KEY" "$SERVER" 'sudo journalctl -u 3am -f -n 50'
