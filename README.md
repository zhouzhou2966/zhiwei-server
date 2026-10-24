# 知微 · 在线学习平台（服务端）

#### 项目简介

> 知微是一个面向工程师的系统化技术学习平台，服务端基于 Spring Cloud 微服务架构，
> 拆分为 **14 个业务服务 + 2 个基础模块**，覆盖用户、课程、学习、考试、交易、支付、
> 营销、搜索、媒资、消息、数据看板等完整业务域。

#### 技术栈

| 分类 | 选型 | 版本 |
|---|---|---|
| 语言 / 运行时 | Java | 11 |
| 基础框架 | Spring Boot | 2.7.2 |
| 微服务 | Spring Cloud | 2021.0.3 |
| 微服务（阿里系） | Spring Cloud Alibaba | 2021.0.1.0 |
| 注册 / 配置中心 | Nacos | 2.1.0 |
| 网关 | Spring Cloud Gateway | — |
| 服务调用 | OpenFeign + LoadBalancer | — |
| 持久层 | MyBatis-Plus | 3.5.5 |
| 数据库 | MySQL | 8.0 |
| 缓存 / 分布式锁 | Redis + Redisson | 3.13.6 |
| 消息队列 | RabbitMQ | 3.8 |
| 分布式事务 | Seata（AT 模式） | 1.5.1 |
| 搜索引擎 | Elasticsearch | 7.12.1 |
| 文档数据库 | MongoDB | — |
| 定时任务 | XXL-JOB | 2.3.0 |
| 流控降级 | Sentinel | — |
| 接口文档 | Knife4j / Swagger | 3.0.3 |
| 工具库 | Hutool | 5.7.17 |

#### 模块划分

| 模块 | 服务名 | 端口 | 职责 |
|---|---|---|---|
| `zw-gateway` | gateway-service | 10010 | 统一入口，路由转发、鉴权、限流 |
| `zw-auth` | auth-service | 8081 | 登录认证，JWT 签发与校验 |
| `zw-user` | user-service | 8082 | 用户、员工、学生、教师，验证码 |
| `zw-search` | search-service | 8083 | 基于 ES 的课程搜索、兴趣推荐 |
| `zw-media` | media-service | 8084 | 文件上传、媒资管理（对象存储 / 点播） |
| `zw-message` | message-service | 8085 | 短信、站内信、通知模板、敏感词、聊天 |
| `zw-course` | course-service | 8086 | 课程、分类、目录，草稿与发布 |
| `zw-pay` | pay-service | 8087 | 支付渠道、支付单、退款、回调通知 |
| `zw-trade` | trade-service | 8088 | 购物车、订单、订单明细、退款申请 |
| `zw-exam` | exam-service | 8089 | 考试、题库 |
| `zw-learning` | learning-service | 8090 | 课表、学习记录、积分、签到、积分榜、笔记、问答 |
| `zw-remark` | remark-service | 8091 | 点赞记录 |
| `zw-promotion` | promotion-service | 8092 | 优惠券、兑换码 |
| `zw-data` | data-service | 8093 | 数据看板、今日数据、Top10 排行 |
| `zw-api` | — | — | 跨服务 Feign 客户端与共享 DTO |
| `zw-common` | — | — | 公共能力自动装配（见下） |

`zw-common` 以 Spring Boot Starter 形式提供，各服务引入即用：

- `autoconfigure/mvc` —— 统一响应体 `R`、全局异常处理、参数校验、JSON 转换
- `autoconfigure/redisson` —— `@Lock` 注解式分布式锁
- `autoconfigure/idempotent` —— `@Idempotent` 接口幂等
- `autoconfigure/mq` —— `RabbitMqHelper`，发送确认 + 失败回滚
- `autoconfigure/mybatis` —— 字段自动填充（创建/更新时间、操作人）
- `autoconfigure/xxljob` —— 定时任务接入
- `autoconfigure/sentinel` —— 流控规则
- `autoconfigure/swagger` —— 接口文档聚合
- `autoconfigure/metrics` / `alert` —— 指标采集与告警

#### 本地启动

##### 1. 启动中间件

| 组件 | 端口 | 用途 |
|---|---|---|
| MySQL | 3306 | 13 个业务库（`zw_user`、`zw_course`、`zw_learning`…） |
| Nacos | 8848 | 注册中心 + 配置中心 |
| RabbitMQ | 5672 / 15672 | 业务消息，需建 vhost |
| Redis | 6379 | 缓存、分布式锁、积分榜 ZSet |
| Elasticsearch | 9200 | 课程搜索 |
| MongoDB | 27017 | 考试记录 |
| Seata Server | 8091 | 分布式事务协调 |
| XXL-JOB | 8880 | 定时任务调度 |

##### 2. 建库导入数据

按服务建库，库名与 Nacos 里的 `zw.jdbc.database` 对应。建表脚本分散在各模块的资源目录下，
例如 `zw-exam/src/main/resources/db/exam_record.sql`、
`zw-message/zw-message-service/src/main/resources/sql/chat.sql`：

```
zw_auth  zw_user  zw_course  zw_learning  zw_exam  zw_trade  zw_pay
zw_promotion  zw_remark  zw_search  zw_media  zw_message  zw_data
```

##### 3. 导入 Nacos 配置

在 Nacos 控制台 `public` 命名空间建以下配置（`yaml` 格式）：

```
shared-spring.yaml     公共 Spring 配置
shared-redis.yaml      Redis 连接
shared-mybatis.yaml    MySQL 连接与 MyBatis-Plus
shared-mq.yaml         RabbitMQ 连接、发送确认、消费重试
shared-feign.yaml      Feign 超时与重试
shared-logs.yaml       日志格式
shared-seata.yaml      Seata 事务组
shared-xxljob.yaml     XXL-JOB 地址与 token

auth-service.yaml      各服务独立配置（数据库名、业务参数、密钥库密码）
user-service.yaml
course-service.yaml
learning-service.yaml
...（每个服务一份）
```

##### 4. 生成密钥库

`zw-auth` 用 JKS 密钥库签发 JWT，密钥库**不入库**，需本地生成后放到
`zw-auth/zw-auth-service/src/main/resources/zw.jks`：

```bash
keytool -genkeypair -alias zw -keyalg RSA -keysize 2048 -validity 3650 \
  -keystore zw.jks -storepass <password> -keypass <password> \
  -dname "CN=zhiwei, OU=dev, O=zhiwei, L=Beijing, ST=Beijing, C=CN"
```

`alias` 必须与 Nacos 中 `encrypt.key-store.alias` 一致，`storepass` 与 `encrypt.key-store.password` 一致。

##### 5. 编译与启动

```bash
# 公共模块必须先装到本地仓库，其他模块依赖它
mvn -pl zw-common -am clean install -DskipTests

# 启动单个服务
mvn -pl zw-gateway spring-boot:run

# 或打包后运行
mvn clean package -DskipTests
java -jar zw-gateway/target/*.jar --spring.profiles.active=dev
```

启动顺序建议：`zw-gateway` 可最后起，其余服务无强依赖；但 `zw-common` 必须先 install。

接口文档：`http://localhost:{port}/doc.html`

#### 核心设计

##### 网关统一鉴权

请求先到 `zw-gateway`，由 `AccountAuthFilter` 解析 token 并把用户信息写入下游请求头，
业务服务不再各自解析 token。`zw-auth` 负责签发与刷新，密钥库在 `zw-auth` 内部，
网关只做验签，不持有私钥。

同一条链路上还有两个过滤器：

- `RateLimitFilter` —— 复用 `RedisRateLimiter`（Redis 令牌桶 + Lua 脚本保证原子性）做接口级限流
- `RequestIdRelayFilter` —— 生成 requestId 透传到下游，配合 MDC 串起整条调用链日志

##### 注解式分布式锁

```java
@Lock(name = "lock:pay:apply:#{#orderId}", waitTime = 5, autoUnlock = true)
public void applyPay(Long orderId) { ... }
```

基于 Redisson 实现，AOP 拦截 `@Lock` 注解，锁 key 支持 SpEL 解析。`leaseTime` 默认 `-1`，
此时 Redisson 看门狗自动续期；也可以指定固定租期，或用 `lockStrategy` 控制拿不到锁时的行为。

##### 接口幂等

`@Idempotent` 注解 + Redis token 机制，防止重复提交。下单、支付回调等写操作都加了。

##### 积分记录按赛季分表

积分记录表 `points_record` 会随赛季切换搬到 `points_record_{season}` 分表，
主表只保留当月数据。周积分查询时若周一与周日跨月，会额外查上月分表并累加：

```java
if (pointsRecordMapper.countTableByName(tableName) == 0) {
    // 分表还没建（月初任务没跑）⇒ 上月数据仍在主表，已经算过，不能再加
    return points;
}
```

分表不存在时直接返回，避免重复累加。

##### MQ 可靠投递

`RabbitMqHelper.send()` 开启 `publisher-confirm-type: correlated` + `publisher-returns: true` +
`template.mandatory: true`：路由失败或 Broker 未 ack 时返回 `false`，调用方据此回滚 Redis 中的业务状态，
避免「缓存已改、消息丢了」的不一致。

##### 分布式事务

跨服务写操作（下单扣库存 + 扣优惠券）走 Seata AT 模式，事务组 `tx-service-group` 在
`shared-seata.yaml` 统一配置。

#### 目录结构

```
zhiwei-server/
├── pom.xml                    父 POM，统一依赖版本
├── zw-common/                 公共能力（Starter）
├── zw-api/                    Feign 客户端 + 共享 DTO
├── zw-gateway/                网关
├── zw-auth/
│   └── zw-auth-service/       认证
├── zw-pay/                    支付（api/service 两层）
│   ├── zw-pay-api/
│   └── zw-pay-service/
├── zw-message/                消息（api/service 两层）
│   ├── zw-message-api/
│   └── zw-message-service/
├── zw-user/                   用户
├── zw-course/                 课程
├── zw-learning/               学习
├── zw-exam/                   考试
├── zw-trade/                  交易
├── zw-promotion/              营销
├── zw-remark/                 点赞
├── zw-search/                 搜索
├── zw-media/                  媒资
└── zw-data/                   数据看板
```

其中 `zw-pay`、`zw-message` 拆成 `-api`（对外 Feign 接口 + DTO）和 `-service`（实现）两层，
`zw-auth` 只有 `-service`，其余模块为单层结构。

#### 说明

- 所有敏感凭据（数据库密码、MQ 密码、密钥库口令、第三方 AppSecret）**不写入代码仓库**，
  统一放在 Nacos 配置中心；仓库里的 `bootstrap.yml` 只保留空值和占位。
- `.gitignore` 已排除 `target/`、`logs/`、`*.jks`、`*.p12`、`.idea/`、`.vscode/`。
