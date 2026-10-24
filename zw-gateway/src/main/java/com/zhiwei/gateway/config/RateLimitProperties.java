package com.zhiwei.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关限流配置
 **/
@Data
@ConfigurationProperties(prefix = "zw.rate-limit")
public class RateLimitProperties {

    /**
     * 是否启用网关限流
     */
    private boolean enabled = true;

    /**
     * 令牌桶的稳态补充速率（个/秒），等价于「单个维度的长期 QPS 上限」
     * <p>取值原则：按峰值实测 QPS 的 1.5~2 倍来设，留出突发余量，
     * 设太小会把正常用户挡在门外，设太大等于没限。
     */
    private int replenishRate = 50;

    /**
     * 令牌桶容量，即允许的瞬时突发流量
     * <p>burstCapacity / replenishRate 约等于"允许多久的高于稳态速率的流量"。
     * 例如 50/100 表示：稳态 50 QPS，可短暂冲到 100 QPS，之后被压回 50。
     */
    private int burstCapacity = 100;

    /**
     * 单次请求消耗的令牌数。
     * <p>所有接口等权时取 1；如果想让重接口更贵，可以按路径分别配置。
     */
    private int requestedTokens = 1;

    /**
     * 触发限流时返回给客户端的提示语
     */
    private String message = "请求过于频繁，请稍后再试";
}
