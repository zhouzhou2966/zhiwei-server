package com.zhiwei.pay.sdk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调用 pay 内部接口时用到的配置，由<b>调用方</b>（trade）在自己的配置中心里提供。
 * <p>
 * <b>为什么需要这个类，而不是直接在 Feign 拦截器里写 {@code @Value}</b>：
 * Spring Cloud OpenFeign 为每个客户端创建的是<b>子 ApplicationContext</b>
 * （{@code NamedContextFactory}），它的 {@code Environment} 是新造的 {@code StandardEnvironment}，
 * <b>不继承</b>父容器的 property sources —— 也就是说 Nacos 里配的 {@code zw.pay.inner-token}
 * 在子上下文里用 {@code @Value} 根本读不到，只会拿到默认值空串，
 * 表现为「拦截器明明注册了、请求却一个 header 都没带」（2026-10-22 实测踩到）。
 * </p>
 * <p>
 * 这个类注册在<b>主</b>上下文，绑定 {@code zw.pay.*}，拦截器通过父容器拿到它，值就是对的。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "zw.pay")
public class PayApiProperties {

    /**
     * 内部服务调用的共享密钥。
     * <p>
     * 必须与 pay 侧 {@code pay-service.yaml} 的 {@code zw.pay.inner-token} 完全一致，
     * 不一致时 pay 的 InnerTokenFilter 一律返回 403。未配置时不会注入 header，
     * 同样会被拒绝（不做「没配就放行」的降级）。
     * </p>
     */
    private String innerToken;
}
