# ============================================================
# TS3AudioBot Docker 镜像
# 基础：Eclipse Temurin 21 JRE (Alpine) + FFmpeg + yt-dlp
# 构建时只需将 TS3AudioBot.jar 放在 Dockerfile 同目录
# ============================================================
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="TS3AudioBot"
LABEL org.opencontainers.image.description="TeamSpeak 3 音频机器人，带 Web 控制台"
LABEL org.opencontainers.image.source="https://github.com/ArthurZhu1992/TS3AudioBot"
LABEL org.opencontainers.image.licenses="Apache-2.0"

# 安装运行时依赖：ffmpeg + yt-dlp
RUN apk add --no-cache \
    ffmpeg \
    yt-dlp \
    wget

# 复制应用
COPY TS3AudioBot.jar /app/TS3AudioBot.jar
COPY ts3Audio-config.toml /app/ts3Audio-config.toml

WORKDIR /app

# 数据持久化目录
RUN mkdir -p /app/data
VOLUME ["/app/data"]

EXPOSE 58913

# 健康检查（Web 控制台端口）
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:58913/ || exit 1

ENTRYPOINT ["java", "-jar", "/app/TS3AudioBot.jar"]
