# 后端镜像（S20，部署方案 v1.1 第 4、7 节）。先打包 jar（CI 的 deploy-staging 里做，测试已经在 CI 跑过），再在仓库根目录构建：
#   mvn -B -ntp -f backend/pom.xml package -DskipTests
#   docker build -f deploy/app.Dockerfile --build-arg FC_VERSION=<提交号> -t forecast-app:<提交号> .
# 镜像里不放任何密钥；数据库、Redis 等配置在运行时由 docker-compose.yml 和服务器的 .env 传入。
FROM eclipse-temurin:25-jre-noble
ARG FC_VERSION=dev
LABEL org.opencontainers.image.source="https://github.com/dollar3651314/forecast" \
      org.opencontainers.image.revision="${FC_VERSION}"
# FC_VERSION：健康检查返回的版本号（部署脚本用它确认新版本已经生效）
# 内存（部署方案第 19 节，2026-10-21 测试服务器内存耗尽事故后调整）：容器上限 768MB，JVM 各部分上限合计约 700MB：
#   堆 400 + 元空间 160 + 代码缓存 64 + 直接内存 64 + 线程栈（约 90 × 512K）；超出就退出、由 Docker 重启，不能靠交换分区撑着
ENV FC_VERSION=${FC_VERSION} \
    FC_STORAGE_DIR=/data/storage \
    TZ=UTC \
    MALLOC_ARENA_MAX=2 \
    JAVA_TOOL_OPTIONS="-Xms256m -Xmx400m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
# 分享卡片（S14，MF-5）：服务器画卡片图片要中文字体（简体、繁体）。文泉驿正黑约 16 MB；拉丁字母用镜像自带的 DejaVu（market/card/CardFonts）
RUN apt-get update && apt-get install -y --no-install-recommends fonts-wqy-zenhei && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 --home /app forecast && mkdir -p /app /data/storage && chown -R forecast /app /data
COPY backend/target/forecast-*.jar /app/app.jar
USER forecast
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
