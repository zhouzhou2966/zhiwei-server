package com.zhiwei.common.autoconfigure.alert;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Webhook 告警自动配置。
 * <p>
 * 无条件注册 {@link WebhookAlertNotifier}（不按 url 是否配置做条件装配）：
 * 未配置 url 时 notifier 内部静默返回，这样业务代码可以无条件注入使用，
 * 不用写 @Autowired(required=false) 之类的兼容逻辑；生产在 Nacos 配置 url 即生效。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(AlertProperties.class)
public class AlertAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WebhookAlertNotifier webhookAlertNotifier(AlertProperties properties) {
        return new WebhookAlertNotifier(properties);
    }
}
