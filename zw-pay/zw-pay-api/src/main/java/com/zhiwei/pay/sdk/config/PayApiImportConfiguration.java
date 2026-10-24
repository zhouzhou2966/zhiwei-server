package com.zhiwei.pay.sdk.config;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * pay-api 的自动装配入口（由 META-INF/spring.factories 加载）。
 * <p>
 * {@code @EnableConfigurationProperties(PayApiProperties.class)} 必须有：
 * {@link PayInnerTokenFeignConfig} 里的拦截器要注入 {@link PayApiProperties}，
 * 而 Feign 子上下文只能拿到<b>父容器里已注册</b>的 bean。不在这里注册，
 * 拦截器就会因为找不到依赖而创建失败，最终表现为「请求不带任何 header」。
 * </p>
 */
@Configuration
@EnableFeignClients(basePackages = "com.zhiwei.pay.sdk.client")
@EnableConfigurationProperties(PayApiProperties.class)
public class PayApiImportConfiguration {

}
