package com.zhiwei.gateway.swagger;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关侧文档聚合入口。
 * <p>
 * knife4j 4.x 的网关模式不再走 springfox 的 {@code /swagger-resources}，
 * 而是请求 {@code /v3/api-docs/swagger-config} 拿到分组列表。
 * 所以这里同时暴露两个路径：
 * <ul>
 *   <li>{@code /v3/api-docs/swagger-config} —— knife4j 4.x 认的地址</li>
 *   <li>{@code /swagger-resources} —— 保留旧地址，避免老书签/老前端直接 404</li>
 * </ul>
 * <p>
 * 两个方法都返回 {@link Mono}：网关是 WebFlux 应用，返回值直接交给 Reactor 调度，
 * 不能在里面阻塞等待路由表（详见 {@link GatewaySwaggerResourceProvider#getUrls()}）。
 */
@RestController
@RequiredArgsConstructor
public class SwaggerResourceController {

    private final GatewaySwaggerResourceProvider gatewaySwaggerResourceProvider;

    @GetMapping("/v3/api-docs/swagger-config")
    public Mono<Map<String, Object>> swaggerConfig() {
        return gatewaySwaggerResourceProvider.getUrls().map(urls -> {
            Map<String, Object> config = new LinkedHashMap<>(4);
            config.put("configUrl", "/v3/api-docs/swagger-config");
            config.put("urls", urls);
            return config;
        });
    }

    @RequestMapping("/swagger-resources")
    public Mono<List<Map<String, String>>> swaggerResources() {
        return gatewaySwaggerResourceProvider.getUrls();
    }
}
