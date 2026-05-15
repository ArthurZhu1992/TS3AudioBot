#!/bin/bash
# TS3AudioBot 启动脚本 (Linux / macOS)
# 用法: ./start.sh [JVM参数...]
#
# 自动使用同目录下的 ffmpeg/ 和 yt-dlp，无需额外配置。

set -e

# 切换到脚本所在目录（保证相对路径正确）
cd "$(dirname "$0")"

# 检测 Java
JAVA=$(command -v java 2>/dev/null || true)
if [ -z "$JAVA" ]; then
    # 检查 JAVA_HOME
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA="$JAVA_HOME/bin/java"
    else
        echo "错误: 未找到 Java。请安装 Java 21+ (推荐: https://adoptium.net/)"
        echo "      或设置 JAVA_HOME 环境变量指向 JDK/JRE 安装目录。"
        exit 1
    fi
fi

# 检查 Java 版本
JAVA_VER=$("$JAVA" -version 2>&1 | head -1 | sed 's/.*version "//;s/".*//' | cut -d. -f1)
if [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
    echo "错误: 需要 Java 21+，当前版本: $("$JAVA" -version 2>&1 | head -1)"
    exit 1
fi

# 查找 JAR
JAR=$(find . -maxdepth 1 -name 'TS3AudioBot-*.jar' -type f | head -1)
if [ -z "$JAR" ]; then
    echo "错误: 未找到 TS3AudioBot-*.jar，请确认与启动脚本在同一目录。"
    exit 1
fi

echo "TS3AudioBot 启动中..."
echo "  Java: $("$JAVA" -version 2>&1 | head -1)"
echo "  JAR:  $JAR"
echo ""

exec "$JAVA" -jar "$JAR" "$@"
