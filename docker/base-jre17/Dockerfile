# 自建 JDK 17 基础镜像（构建机无外网时用）
#
# 为什么需要它：
#   本项目的服务镜像要求 JDK 17，但构建机连不上 Docker Hub
#   （registry-1.docker.io 超时，阿里云加速器已停服），
#   而本地只有 openjdk:11.0-jre-buster 这一个带 shell 的 JDK 镜像。
#   所以用「本地 openjdk:11 + 离线 JDK 17 包」拼一个 17 的基础镜像，
#   上层 Dockerfile 只要 FROM zhiwei-jre17:17 即可，不再依赖外网。
#
# 用法（在仓库根目录执行一次）：
#   1. 下载 Linux x64 的 JDK 17 压缩包，放到本文件同目录：
#      https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/linux/
#      文件名形如 OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz
#   2. cd docker/base-jre17 && mv OpenJDK17U-jdk_x64_linux_hotspot_*.tar.gz jdk17.tar.gz
#   3. docker build -t zhiwei-jre17:17 .
#   4. docker run --rm zhiwei-jre17:17 java -version   # 应输出 17.x
#
# 说明：
#   - 之所以不删掉镜像里自带的 JDK 11，是为了省事：多占约 200MB，
#     换来的是不用处理 openjdk 镜像内部的软链接关系，出问题概率更低。
#   - 时区在这里设好，上层 Dockerfile 就不用再 RUN 一次。

FROM openjdk:11.0-jre-buster
LABEL maintainer="zhiwei"
LABEL description="JDK 17 runtime base image built offline from a local JDK tarball"

ENV TZ=Asia/Shanghai

COPY jdk17.tar.gz /tmp/
RUN tar -xzf /tmp/jdk17.tar.gz -C /opt \
    && rm -f /tmp/jdk17.tar.gz \
    && mv /opt/jdk-17* /opt/jdk17

ENV JAVA_HOME=/opt/jdk17
ENV PATH=$JAVA_HOME/bin:$PATH
