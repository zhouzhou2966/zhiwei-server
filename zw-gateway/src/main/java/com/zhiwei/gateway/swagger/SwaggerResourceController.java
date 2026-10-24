package com.zhiwei.gateway.swagger;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 */
@RestController
@RequiredArgsConstructor
public class SwaggerResourceController {

    private final GatewaySwaggerResourceProvider gatewaySwaggerResourceProvider;

    @GetMapping("/v3/api-docs/swagger-config")
    public ResponseEntity<Map<String, Object>> swaggerConfig() {
        Map<String, Object> config = new LinkedHashMap<>(4);
        config.put("configUrl", "/v3/api-docs/swagger-config");
        config.put("urls", gatewaySwaggerResourceProvider.getUrls());
        return new ResponseEntity<>(config, HttpStatus.OK);
    }

    @RequestMapping("/swagger-resources")
    public ResponseEntity<List<Map<String, String>>> swaggerResources() {
        return new ResponseEntity<>(gatewaySwaggerResourceProvider.getUrls(), HttpStatus.OK);
    }
}
