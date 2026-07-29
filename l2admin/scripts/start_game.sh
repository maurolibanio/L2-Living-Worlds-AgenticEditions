#!/bin/bash
# start_game.sh - Start GameServer with nohup and save PID
# Uses symlink path /root/l2server-runtime

GAME_DIR="/root/l2server-runtime/game"
JAVA_CMD="/usr/lib/jvm/temurin-25-jdk-amd64/bin/java"
JAR_PATH="/root/l2server-runtime/libs/GameServer.jar"
PID_FILE="/tmp/l2-game.pid"
LOG_FILE="/root/l2server-runtime/game/log/stdout.log"

cd "$GAME_DIR" || exit 1

nohup $JAVA_CMD -server   -Dfile.encoding=UTF-8   -Djava.util.logging.manager=org.l2jmobius.log.ServerLogManager   -Dorg.slf4j.simpleLogger.log.com.zaxxer.hikari=warn   -XX:+UseZGC   -Xms1g -Xmx2g   -jar "$JAR_PATH"   > "$LOG_FILE" 2>&1 &

GAME_PID=$!
echo $GAME_PID > "$PID_FILE"
echo "GameServer started with PID $GAME_PID"
