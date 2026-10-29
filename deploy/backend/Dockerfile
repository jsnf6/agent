# 后端运行时镜像：仅包含 JRE 与 Spring Boot 可执行 jar
# jar 由仓库根目录的 scripts/build.ps1 通过 Maven 构建后复制到本目录
FROM eclipse-temurin:17-jre

WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms512m -Xmx1536m -Dfile.encoding=UTF-8"

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY app.jar /app/app.jar

EXPOSE 9082

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]