#! /bin/sh
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
echo "copy xx.jar from ${BASE_PATH}/${PROJECT_PATH}"
rm -f app.jar
cp ${BASE_PATH}/${PROJECT_PATH}/target/${PROJECT_NAME}.jar ./app.jar ||  exit 1

echo "begin to build ${PROJECT_NAME} image ！！"

[ -n "`docker ps -a | grep ${CONTAINER_NAME}`" ] && docker rm -f ${CONTAINER_NAME}
[ -n "`docker images | grep ${CONTAINER_NAME}`" ] && docker rmi ${IMAGE_NAME}

docker build -t ${IMAGE_NAME} . || exit 1
echo "${PROJECT_NAME} image build success，java_opts = $JAVA_OPTS ！！^_^"

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