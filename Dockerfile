# JDK 17 是硬要求：Boot 3.5 的产物是 class file version 61，JDK 11 只认到 55，启动即 UnsupportedClassVersionError。
# eclipse-temurin 是 Adoptium 的官方镜像（替代已停止维护的 openjdk 官方镜像）。
# 若构建环境拉不到 Docker Hub，可换成本地仓库里的等价 17-jre 镜像，或 openjdk:17-jre。
FROM eclipse-temurin:17-jre
LABEL maintainer="zhiwei"
ENV JAVA_OPTS=""
# 设定时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app
ADD app.jar /app/app.jar

# JAVA_OPTS 放在 -jar 之前是标准写法。原写法把选项写在 -jar 之后其实也能工作
# （实测 java -jar -Xmx512m app.jar 的堆上限确实是 512M，JDK 启动器会继续解析 - 开头的选项），
# 只是不符合直觉、容易让人误以为选项会被当成 jar 文件名，这里统一成标准位置。
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
