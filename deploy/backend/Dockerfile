# 后端运行时镜像：JRE + Playwright Chromium。
# jar 由仓库根目录的 scripts/build.ps1 或 deploy/scripts/build-images.ps1 构建后复制到本目录。
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1536m -Dfile.encoding=UTF-8" \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY app.jar /app/app.jar

# Chromium 安装层：下载目录走 BuildKit 缓存挂载，业务 jar 变化时无需重新下载浏览器。
# 安装完成后复制到 /ms-playwright，运行期只依赖镜像内的浏览器目录。
RUN --mount=type=cache,target=/root/.cache/ms-playwright-install \
    apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && mkdir -p /tmp/playwright-install \
    && unzip -q /app/app.jar 'BOOT-INF/lib/*' -d /tmp/playwright-install \
    && PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright-install \
       java -cp '/tmp/playwright-install/BOOT-INF/lib/*' com.microsoft.playwright.CLI install --with-deps chromium \
    && mkdir -p /ms-playwright \
    && cp -a /root/.cache/ms-playwright-install/. /ms-playwright/ \
    && rm -rf /tmp/playwright-install /var/lib/apt/lists/*

EXPOSE 9082

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]