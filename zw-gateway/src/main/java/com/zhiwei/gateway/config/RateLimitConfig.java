package com.zhiwei.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static com.zhiwei.auth.common.constants.JwtConstants.USER_HEADER;

/**
 * 限流相关装配
 **/
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /**
     * 限流器：显式构造，把 {@link RateLimitProperties} 里的配额写进 {@code defaultConfig}。
     *
     * <p><b>为什么必须自己定义这个 Bean</b>：Spring Cloud Gateway 自动配置的 {@code RedisRateLimiter}
     * 走的是 {@code RedisRateLimiter(redisTemplate, script, configurationService)} 这条构造路径，
     * 它<b>不会设置 defaultConfig</b>（只有 {@code RedisRateLimiter(int,int,int)} 会设）。
     * 而本项目的限流是「全局单一配额」——{@link com.zhiwei.gateway.filter.RateLimitFilter} 里的
     * routeId 是固定常量，永远不会出现在 {@code spring.cloud.gateway.routes[].filters} 里，
     * 于是 {@code AbstractRateLimiter.loadConfiguration(routeId)} 既查不到该 route 的配置、
     * 也拿不到 defaultConfig，直接抛
     * {@code IllegalArgumentException: No Configuration found for route zw-gateway or defaultFilters}，
     * 表现为<b>经网关的所有请求都返回 500</b>（HTTP 状态码仍是 200，只是 body 是错误结构）。
     *
     * <p>用 int 构造器即可：它会设置 defaultConfig；而 redisTemplate 与限流 Lua 脚本
     * 由 {@code setApplicationContext} 从容器里自动取（{@code ReactiveStringRedisTemplate}、
     * {@code redisRequestRateLimiterScript}），不需要手动注入。
     *
     * <p>自动配置里的同名 Bean 带 {@code @ConditionalOnMissingBean}，会自动让位，不会冲突。
     */
    @Bean
    public RedisRateLimiter redisRateLimiter(RateLimitProperties properties) {
        return new RedisRateLimiter(
                properties.getReplenishRate(),
                properties.getBurstCapacity(),
                properties.getRequestedTokens());
    }

    /**
     * 限流维度：登录用户按 userId 计数，未登录按来源 IP 计数
     * <p>为什么不是单纯按 IP：校园、公司出口 NAT 下成百上千人共用一个公网 IP，
     * 只按 IP 限流会把正常用户误伤；反过来只按 userId 限流，匿名流量（登录、注册、验证码）
     * 就完全没有约束。所以两级兜底：能识别身份就按身份，识别不了就按 IP。
     * <p>注意这里的 IP 是网关看到的对端地址，生产环境如果网关前面还有 Nginx/SLB，
     * 需要改成解析 {@code X-Forwarded-For} 的第一跳，否则所有人都会被算作同一个 IP。
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst(USER_HEADER);
            if (StringUtils.hasText(userId)) {
                return Mono.just("user:" + userId);
            }
            return Mono.just("ip:" + resolveIp(exchange));
        };
    }

    private String resolveIp(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String forwarded = headers.getFirst("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            // X-Forwarded-For 可能是 "client, proxy1, proxy2"，取第一跳即真实客户端
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        if (exchange.getRequest().getRemoteAddress() == null) {
            return "unknown";
        }
        return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
}
