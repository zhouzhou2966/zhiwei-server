package com.zhiwei.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiwei.common.domain.R;
import com.zhiwei.gateway.config.RateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关限流过滤器
 *
 * <p><b>为什么不用 Spring Cloud Gateway 自带的 {@code RequestRateLimiter} 过滤器？</b>
 * 自带过滤器在拒绝请求时只做 {@code setComplete()}，返回一个<b>空 body 的 429</b>，
 * 前端拿不到统一结构（本项目所有接口都是 {@code R{code,msg,data}}），
 * 要么前端为 429 单独写一套解析，要么用户只看到白屏。
 * 它的 {@code apply()} 里拒绝分支是硬编码的，没法通过配置或继承改掉。
 * <p>所以这里复用官方成熟的限流算法实现 {@link RedisRateLimiter}（Redis 令牌桶 + Lua 脚本保证原子性），
 * 但把"拦截后怎么响应"这一步拿回自己手里 —— 算法用官方的，出口由自己控制。
 *
 * <p><b>为什么这个过滤器必须排在鉴权之后</b>：
 * 限流维度要用到用户身份（{@code USER_HEADER}），而这个请求头是 {@link AccountAuthFilter}
 * 解析 token 之后才写进去的。GlobalFilter 按 order 从小到大执行，
 * 所以这里取 {@code HIGHEST_PRECEDENCE + 20}，落在鉴权（{@code +10}）之后、
 * 路由转发之前 —— 既拿得到身份，又能在流量进入下游服务之前拦掉。
 **/
@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    /**
     * 限流的逻辑路由名。
     * <p>RedisRateLimiter 用它和限流维度拼成 Redis key（{@code request_rate_limiter.{routeId}.{key}}），
     * 网关只有一个限流器，用固定值即可；如果将来想给不同路由配不同速率，
     * 这里换成实际的 routeId（{@code exchange.getAttribute(GATEWAY_ROUTE_ATTR)}）即可。
     */
    private static final String ROUTE_ID = "zw-gateway";

    private final ObjectProvider<RedisRateLimiter> rateLimiterProvider;
    private final KeyResolver keyResolver;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(ObjectProvider<RedisRateLimiter> rateLimiterProvider,
                           KeyResolver keyResolver,
                           RateLimitProperties properties,
                           ObjectMapper objectMapper) {
        this.rateLimiterProvider = rateLimiterProvider;
        this.keyResolver = keyResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        // 依赖缺失时降级为"放行 + 告警"，限流是保护性能力，不应该反过来让网关起不来
        RedisRateLimiter rateLimiter = rateLimiterProvider.getIfAvailable();
        if (rateLimiter == null) {
            log.warn("未找到 RedisRateLimiter，网关限流已跳过，请检查 spring-boot-starter-data-redis-reactive 依赖");
            return chain.filter(exchange);
        }
        return keyResolver.resolve(exchange)
                .defaultIfEmpty("anonymous")
                .flatMap(key -> rateLimiter.isAllowed(ROUTE_ID, key))
                .flatMap(response -> {
                    // 把配额信息透出给客户端：前端可据此提示"操作过于频繁"，
                    // 排障时也能直接看到剩余令牌数，不用去翻 Redis
                    response.getHeaders().forEach((k, v) -> exchange.getResponse().getHeaders().add(k, v));
                    if (response.isAllowed()) {
                        return chain.filter(exchange);
                    }
                    return deny(exchange);
                });
    }

    /**
     * 被限流时的响应：统一成项目约定的 R 结构，前端无需为 429 写特殊分支
     */
    private Mono<Void> deny(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = serializeBody();
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private byte[] serializeBody() {
        try {
            return objectMapper.writeValueAsBytes(
                    R.error(HttpStatus.TOO_MANY_REQUESTS.value(), properties.getMessage()));
        } catch (JsonProcessingException e) {
            // 兜底：序列化失败也必须给出一个合法 JSON，不能让 429 变成空 body
            log.warn("限流响应序列化失败，使用兜底结构", e);
            return ("{\"code\":429,\"msg\":\"" + properties.getMessage() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
