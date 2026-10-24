package com.zhiwei.common.autoconfigure.swagger;

import cn.hutool.core.convert.ConverterRegistry;
import com.zhiwei.common.utils.TjTemporalConverter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 接口文档配置（OpenAPI 3 / springdoc）。
 * <p>
 * 旧实现基于 springfox 3 + knife4j 3。springfox 早已停止维护，且整条链路基于 javax，
 * 在 Spring Boot 3 的 jakarta 环境下无法运行，因此整体换成 springdoc。
 * 与旧实现的三点对应关系：
 * <ul>
 *   <li>原来的 {@code Docket} + {@code basePackage} → {@link GroupedOpenApi#packagesToScan}</li>
 *   <li>原来的 {@code ApiInfoBuilder} → {@link Info}</li>
 *   <li>原来的两个 springfox 插件（把返回值包成 R）→ {@link GlobalOpenApiCustomizer}</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "zw.swagger", name = "enable", havingValue = "true")
@EnableConfigurationProperties(SwaggerConfigProperties.class)
public class Knife4jConfiguration {

    @Resource
    private SwaggerConfigProperties swaggerConfigProperties;

    /**
     * 文档元信息。
     * 同时声明 Bearer 鉴权方案，knife4j 页面右上角就会出现 Authorize 按钮，
     * 调试需要登录态的接口时不用再手动往 Header 里贴 token。
     */
    @Bean
    public OpenAPI zwOpenAPI() {
        Contact contact = new Contact()
                .name(swaggerConfigProperties.getContactName())
                .url(swaggerConfigProperties.getContactUrl())
                .email(swaggerConfigProperties.getContactEmail());
        return new OpenAPI()
                .info(new Info()
                        .title(swaggerConfigProperties.getTitle())
                        .description(swaggerConfigProperties.getDescription())
                        .version(swaggerConfigProperties.getVersion())
                        .contact(contact))
                .components(new Components().addSecuritySchemes("Authorization",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("Authorization"));
    }

    /**
     * 按 zw.swagger.package-path 收敛扫描范围。
     * 不限定包会把 Feign 客户端接口、SDK 里的 Controller 一并扫进来，文档里出现大量重复条目。
     */
    @Bean
    public GroupedOpenApi zwGroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("default")
                .packagesToScan(swaggerConfigProperties.getPackagePath())
                .build();
    }

    /**
     * 把 200 响应包一层 R。
     * <p>
     * 项目里所有 Controller 实际返回的都是 {@code R<T>}，但方法签名上写的常常是 {@code T}，
     * 不包一层的话文档展示的响应结构与真实返回对不上。
     * 旧实现靠 springfox 的两个 OperationBuilderPlugin 做这件事，springdoc 没有对应 SPI，
     * 改成在文档生成完成后统一改写 200 响应的 schema。
     */
    @Bean
    @ConditionalOnProperty(prefix = "zw.swagger", name = "enableResponseWrap", havingValue = "true")
    public GlobalOpenApiCustomizer zwResponseWrapCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(this::wrapAllOperations);
        };
    }

    private void wrapAllOperations(PathItem pathItem) {
        for (Operation operation : pathItem.readOperations()) {
            wrapResponse(operation);
        }
    }

    private void wrapResponse(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            return;
        }
        ApiResponse ok = responses.get("200");
        if (ok == null || ok.getContent() == null) {
            return;
        }
        for (MediaType mediaType : ok.getContent().values()) {
            mediaType.setSchema(wrap(mediaType.getSchema()));
        }
    }

    private Schema<?> wrap(Schema<?> data) {
        ObjectSchema wrapper = new ObjectSchema();
        wrapper.addProperty("code", new IntegerSchema().example(200)
                .description("业务状态码，200-成功，其它-失败"));
        wrapper.addProperty("msg", new StringSchema().example("OK").description("响应消息"));
        wrapper.addProperty("data", data == null ? new ObjectSchema() : data);
        wrapper.addProperty("requestId", new StringSchema().example("1af123c11412e").description("请求id"));
        return wrapper;
    }

    {
        // hutool的日期转换器加载
        ConverterRegistry converterRegistry = ConverterRegistry.getInstance();
        converterRegistry.putCustom(LocalDateTime.class, new TjTemporalConverter(LocalDateTime.class));
    }
}
