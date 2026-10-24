package com.zhiwei.common.autoconfigure.alert;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webhook 告警配置项（绑定前缀 zw.alert）
 * <p>
 * 凭据约定：webhook 地址属于敏感信息，一律放 Nacos 的服务配置里下发，
 * 代码与本地配置文件只留空 key。未配置 url 时告警静默关闭，不影响任何业务。
 * </p>
 * <p>
 * 为什么超时和重试都做得这么保守：钉钉自定义机器人的限制是「每个机器人每分钟最多 20 条，
 * 超过会被限流 10 分钟」，而告警是同步发送、跑在消费线程里的 —— 宁可少发一条，
 * 也不能重试风暴把自己限流，更不能拖住主流程。
 * </p>
 */
@Data
@ConfigurationProperties("zw.alert")
public class AlertProperties {

    /**
     * 群机器人 webhook 地址。为空 = 告警关闭（开发环境默认就是这个状态）
     */
    private String webhookUrl;

    /**
     * 机器人类型：dingtalk（钉钉）| wecom（企业微信，text 格式与钉钉相同）| feishu（飞书）
     */
    private String webhookType = "dingtalk";

    /**
     * 同一告警项的最小重发间隔（秒）。
     * ※ 防告警风暴：MQ 故障或 Redis 故障期间，error 队列/补偿任务可能每分钟触发几十次，
     *   不做节流会把群刷爆，真正要人看的那条反而被淹没。默认 5 分钟一条。
     */
    private long throttleSeconds = 300;

    /**
     * 连接超时（毫秒）。告警是旁路能力，连不上就不发，不能占着业务线程一直等
     */
    private int connectTimeoutMs = 1000;

    /**
     * 读超时（毫秒）。钉钉正常响应在 200ms 内，2 秒还没回来基本就是网络有问题
     */
    private int readTimeoutMs = 2000;

    /**
     * 失败重试次数（不含首次）。默认 1 次，且只对「网络异常 / 5xx」这类瞬时故障重试；
     * 业务错误（关键词不匹配、token 失效）重试多少次结果都一样，直接放弃。
     * ※ 不要调大：钉钉有每分钟 20 条的限制，重试风暴会被限流 10 分钟。
     */
    private int maxRetry = 1;

    /**
     * 重试前的固定等待时间（毫秒）
     */
    private long retryIntervalMs = 500;
}
