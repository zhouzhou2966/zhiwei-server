package com.zhiwei.pay.sdk.config;

import com.zhiwei.pay.sdk.constants.PayConstants;
import feign.RequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * pay 内部接口的调用密钥注入。
 * <p>
 * 背景：pay 的 {@code /ps/**} 整条链路没有接 auth sdk、网关也不做鉴权，
 * 而其中的申请退款、增改支付渠道都是写操作。pay 侧已用 {@code X-Inner-Token} 请求头统一校验，
 * 这里负责把密钥自动带在每一次 Feign 调用上。
 * </p>
 * <p>
 * <b>为什么这个类不能加 {@code @Configuration}</b>：
 * 它只通过 {@code @FeignClient(configuration = ...)} 挂到 pay-service 这一个客户端上。
 * 一旦加了 {@code @Configuration} 并被主容器扫到，就会变成<b>全局</b> {@link RequestInterceptor}，
 * 把内部密钥发给所有第三方 Feign 调用（例如发给 course、promotion 的请求），密钥等于外泄。
 * </p>
 * <p>
 * <b>为什么密钥要从 {@link PayApiProperties} 注入、而不是在这里写 {@code @Value}</b>：
 * 本类运行在 Feign 为 pay-service 单独创建的子上下文里，子上下文的 {@code Environment}
 * 不继承 Nacos 的配置，{@code @Value("${zw.pay.inner-token:}")} 只会拿到空串，
 * 结果是拦截器注册成功但请求一个 header 都不带（2026-10-22 实测踩到）。
 * {@code PayApiProperties} 注册在主上下文，子上下文通过父容器拿 bean，值才是对的。
 * </p>
 */
public class PayInnerTokenFeignConfig {

    private static final Logger log = LoggerFactory.getLogger(PayInnerTokenFeignConfig.class);

    @Bean
    public RequestInterceptor payInnerTokenInterceptor(PayApiProperties payApiProperties) {
        String innerToken = payApiProperties.getInnerToken();
        // 拦截器只在首次调用 pay 时创建一次，这条日志用于确认密钥到底有没有绑定上
        if (StringUtils.hasText(innerToken)) {
            log.info("pay 内部接口密钥已就绪（长度 {}），将随每次 Feign 调用发送 {}", innerToken.length(),
                    PayConstants.INNER_TOKEN_HEADER);
        } else {
            log.warn("未绑定 zw.pay.inner-token，调用 pay 内部接口会被 403 拒绝");
        }
        return template -> {
            if (StringUtils.hasText(innerToken)) {
                template.header(PayConstants.INNER_TOKEN_HEADER, innerToken);
            }
        };
    }
}
