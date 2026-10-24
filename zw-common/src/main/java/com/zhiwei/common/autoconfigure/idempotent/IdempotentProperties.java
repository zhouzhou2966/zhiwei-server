package com.zhiwei.common.autoconfigure.idempotent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 幂等框架配置
 **/
@Data
@ConfigurationProperties(prefix = "zw.idempotent")
public class IdempotentProperties {

    /**
     * 是否启用幂等框架，默认开启
     */
    private boolean enabled = true;

    /**
     * 所有幂等键的公共前缀，便于在 Redis 中归类排查
     */
    private String keyPrefix = "idem:";

    /**
     * 令牌模式从请求头中读取令牌的名称
     */
    private String tokenHeader = "Idempotent-Token";

    /**
     * 令牌有效期（秒）。超过该时长未使用的令牌自动作废，
     * 避免客户端申请后不提交、令牌长期堆积在 Redis 中
     */
    private long tokenTimeout = 300;

    /**
     * 重复请求命中"处理中"状态时的提示语
     */
    private String processingMessage = "请求正在处理中，请勿重复提交";
}
