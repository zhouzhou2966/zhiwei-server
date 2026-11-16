#! /bin/sh
#
# 单个服务的「取 jar → 打镜像 → 换容器」脚本。17 个 Jenkins job 共用这一份，
# 靠参数区分服务：
#   startup.sh -c zw-course -n zw-course -d zw-course -p 8086
#     -c 容器名/镜像名   -n maven 模块名（决定 jar 文件名）
#     -d workspace 下的模块目录   -p 端口
#     -o 覆盖 JAVA_OPTS   -a 调试端口（非 0 时以 debug 模式起）
cd /usr/local/src/script || exit 1
BASE_PATH='/usr/local/src/jenkins/workspace/zw-dev-build'
PROJECT_NAME=""
PROJECT_PATH=''
CONTAINER_NAME=""
# Boot 3 + JDK 17 下单个服务实测 RSS 380~450MB，300m 上限会 OOM，这里放到 512m
JAVA_OPTS="-Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m"
PORT=8080
DEBUG_PORT=0
while getopts "c:n:d:p:o:a:" opt; do
    case $opt in
         c)
            CONTAINER_NAME=$OPTARG
          ;;
         n)
            PROJECT_NAME=$OPTARG
          ;;
         d)
            PROJECT_PATH=$OPTARG
          ;;
         p)
            PORT=$OPTARG
          ;;
         o)
            [ -n "$OPTARG" ] && JAVA_OPTS=$OPTARG
          ;;
         a)
            [ -n "$OPTARG" ] && DEBUG_PORT=$OPTARG
          ;;
         ?)
            echo "unkonw argument"
            exit 1
          ;;
    esac
done
if [ "$DEBUG_PORT" = "0" ]; then
  JAVA_OPTS=$JAVA_OPTS
else
  JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
fi
IMAGE_NAME="${CONTAINER_NAME}:latest"
TMP_TAG="${CONTAINER_NAME}:build"
SRC_JAR="${BASE_PATH}/${PROJECT_PATH}/target/${PROJECT_NAME}.jar"

# ---------------------------------------------------------------- 构建上下文
# 每个容器一份独立上下文，**绝不能共用**。
#
# 旧实现把 jar 复制成固定文件名 ./app.jar 放进 /usr/local/src/script 再 docker build .，
# 而这个目录是 17 个服务共用的；Jenkins 的 concurrentBuild=false 只保证「同一个 job 不重入」，
# 不同 job 之间照样并行。于是 A 拷完自己的 jar、B 又拷自己的覆盖掉，
# A 的 docker build 就读到了 B 的 app.jar。
#
# 实测后果（2026-11-16）：zw-course#74 与 zw-search#49 同一秒启动，
# zw-course:latest 里装成了 zw-search.jar（Implementation-Title: zw-search）。
# 容器 Up、端口 8086 对、日志也有 "is running"，只有网关 /cs/** 一直「服务不存在」，
# 排查成本极高。所以上下文必须按容器隔离。
# 目录放在 script/ 之外：zw-dev-build 的收尾步骤会 `rm -rf *` 清空 script/ 再倒入
# Dockerfile 和本脚本，构建上下文搁在里面会被误删。
CTX_DIR="/usr/local/src/build/${CONTAINER_NAME}"
rm -rf "${CTX_DIR}" || exit 1
mkdir -p "${CTX_DIR}" || exit 1
cp /usr/local/src/script/Dockerfile "${CTX_DIR}/Dockerfile" || exit 1

echo "copy ${SRC_JAR}"
echo "  -> ${CTX_DIR}/app.jar"
if [ ! -f "${SRC_JAR}" ]; then
  echo "FATAL: ${SRC_JAR} 不存在（dev-build 没跑过，或者 -n/-d 参数写错了）"
  exit 1
fi
cp "${SRC_JAR}" "${CTX_DIR}/app.jar" || exit 1
SRC_SUM=`md5sum "${CTX_DIR}/app.jar" | awk '{print $1}'`

echo "begin to build ${PROJECT_NAME} image ！！"
# 先打到临时 tag，校验通过才提升为 :latest —— 免得校验失败时留下一个错的最新镜像
docker rmi "${TMP_TAG}" > /dev/null 2>&1
docker build -t "${TMP_TAG}" "${CTX_DIR}" || exit 1

# 校验镜像里装的确实是本服务的 jar，把「串包」这种静默错误变成红色构建。
# 只比 md5 不比文件名：文件名永远叫 app.jar，看不出内容是谁。
IMG_SUM=`docker run --rm --entrypoint cat "${TMP_TAG}" /app/app.jar 2> /dev/null | md5sum | awk '{print $1}'`
if [ -z "${IMG_SUM}" ] || [ "${IMG_SUM}" != "${SRC_SUM}" ]; then
  echo "FATAL: ${TMP_TAG} 里的 app.jar 与源 jar 不一致，疑似构建上下文被并发覆盖"
  echo "  src ${SRC_JAR}  md5=${SRC_SUM}"
  echo "  img /app/app.jar md5=${IMG_SUM}"
  exit 1
fi
echo "jar 校验通过：md5 ${SRC_SUM}"
docker tag "${TMP_TAG}" "${IMAGE_NAME}" || exit 1
echo "${PROJECT_NAME} image build success，java_opts = $JAVA_OPTS ！！^_^"

# 精确匹配容器名。旧写法 `docker ps -a | grep zw-trade` 会连 zw-trade-debug 一起命中，
# 机器上确实同时存在 zw-trade / zw-trade-debug、zw-gateway / zw-gateway-debug。
if docker ps -a --format '{{.Names}}' | grep -qx "${CONTAINER_NAME}"; then
  docker rm -f "${CONTAINER_NAME}" || exit 1
fi
# 临时 tag 已经没用了，:latest 还指着同一个镜像，删 tag 不会删镜像
docker rmi "${TMP_TAG}" > /dev/null 2>&1

echo "begin to create container ${CONTAINER_NAME}，port: ${PORT} ！！"

# --restart unless-stopped：机器重启后容器能自己回来，不用手工 docker start。
# 不用 always 是有意的 —— always 连「手动 docker stop 过的容器」也会在 daemon 重启后拉起，
# 这台机器只有 7.8G 内存、每个服务占 400~500MB，临时停几个服务省内存后重启机器会一次性全起来；
# unless-stopped 尊重手动 stop，行为更可预测。
# 注意：zw-course / zw-trade 这类依赖 Seata 的服务，Seata 的 TMClient 是 fail-fast，
# 机器刚起时 seata-server 还没注册进 Nacos 会直接抛 no available service found in cluster 退出，
# 靠 docker 的指数退避重试（最长 1 分钟一次）等到 seata 就绪，属于预期行为。
if [ "$DEBUG_PORT" = "0" ]; then
  echo "run in normal mode"
  docker run -d --name ${CONTAINER_NAME} \
   -p "${PORT}:${PORT}" \
   -e JAVA_OPTS="${JAVA_OPTS}" \
   --memory 1024m --memory-swap -1 \
   --restart unless-stopped \
   --network zhiwei-net ${IMAGE_NAME} \
  || exit 1
else
  echo "run in debug mode"
  docker run -d --name ${CONTAINER_NAME} \
   -p "${PORT}:${PORT}" \
   -p ${DEBUG_PORT}:5005 \
   -e JAVA_OPTS="${JAVA_OPTS}" \
   --restart unless-stopped \
   --network zhiwei-net ${IMAGE_NAME} \
  || exit 1
fi
echo "container is running now !! ^_^"
exit 0
