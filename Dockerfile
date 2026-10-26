# JDK 17 是硬要求：Boot 3.5 的产物是 class file version 61，JDK 11 只认到 55，启动即 UnsupportedClassVersionError。
# 基础镜像用本地自建的 zhiwei-jre17：这台构建机连不上 Docker Hub（registry-1.docker.io 超时，
# 阿里云加速器也已停服），只能用本地已有的 openjdk:11 镜像 + 离线 JDK 17 包拼一个出来。
# 自建方式见 docker/base-jre17/Dockerfile，执行一次即可。
# 在有外网的环境里，把这一行换回 FROM eclipse-temurin:17-jre 行为等价。
FROM zhiwei-jre17:17
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
