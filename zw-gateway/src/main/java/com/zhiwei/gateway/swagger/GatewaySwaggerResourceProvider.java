package com.zhiwei.gateway.swagger;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关侧接口文档聚合。
 * <p>
 * 原来基于 springfox 的 {@code SwaggerResourcesProvider}，Boot 3 之后 springfox 不可用，
 * 改成直接产出 springdoc / knife4j 4.x 认的 {@code swagger-config} 结构。
 * 每个下游服务在 zw-common 里都注册了名为 {@code default} 的 GroupedOpenApi，
 * 所以文档地址是 {@code /{routeId}/v3/api-docs/default}。
 */
@Component
@Primary
@RequiredArgsConstructor
public class GatewaySwaggerResourceProvider {

    /**
     * springdoc 分组文档的后缀，与 zw-common 里 GroupedOpenApi.builder().group("default") 对应
     */
    private static final String API_DOCS_SUFFIX = "/v3/api-docs/default";

    /**
     * 路由定位器
     */
    private final RouteLocator routeLocator;

    /**
     * 网关应用名称
     */
    @Value("${spring.application.name}")
    private String gatewayName;

    /**
     * 生成 knife4j 需要的 urls 列表。
     * 路由 id 与路由 path 前缀保持一致，所以直接拿 routeId 当上下文路径。
     */
    public List<Map<String, String>> getUrls() {
        // 1.收集下游服务：取路由 Uri 的 Host 作为服务名，排除网关自己（否则会自我聚合，页面里多一个空分组）
        //    路由表在内存里、数量固定，这里 block 一次拿全量是可接受的；
        //    原实现用 subscribe 异步填充 Map，返回时 Map 可能还是空的，是个隐藏的竞态。
        Map<String, String> servers = new LinkedHashMap<>();
        routeLocator.getRoutes()
                .filter(route -> route.getUri().getHost() != null)
                .filter(route -> !gatewayName.equals(route.getUri().getHost()))
                .collectList()
                .block()
                .forEach(route -> servers.put(route.getUri().getHost(), route.getId()));

        // 2.按 knife4j 4.x 的 swagger-config 结构组装
        List<Map<String, String>> urls = new ArrayList<>(servers.size());
        servers.forEach((name, path) -> {
            Map<String, String> item = new LinkedHashMap<>(4);
            item.put("name", name);
            item.put("url", "/" + path + API_DOCS_SUFFIX);
            urls.add(item);
        });
        return urls;
    }
}
