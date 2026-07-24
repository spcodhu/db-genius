# syntax=docker/dockerfile:1

###############################################################################
# 多阶段构建：build 阶段用 Maven 打包，运行阶段只保留 JRE + jar，镜像更小。
# 前提：构建上下文是本地已 clone 好的项目根目录（git pull 由外层脚本负责）。
###############################################################################

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 拷入 Maven 配置：将中央仓库代理到阿里云，加速依赖下载
COPY deploy/settings.xml /root/.m2/settings.xml

# 先拷 pom 预热依赖缓存：源码变动时无需重新下载依赖
COPY pom.xml ./
COPY db-genius-common/pom.xml db-genius-common/
COPY db-genius-model/pom.xml db-genius-model/
COPY db-genius-service/pom.xml db-genius-service/
COPY db-genius-agent/pom.xml db-genius-agent/
COPY db-genius-web/pom.xml db-genius-web/
RUN mvn -q -B -s /root/.m2/settings.xml dependency:go-offline -DskipTests || true

# 再拷全部源码并打包
COPY . .
RUN mvn -q -B -s /root/.m2/settings.xml clean package -DskipTests

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /build/db-genius-web/target/db-genius-web-*.jar app.jar

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8109

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=$SPRING_PROFILES_ACTIVE -jar app.jar"]
