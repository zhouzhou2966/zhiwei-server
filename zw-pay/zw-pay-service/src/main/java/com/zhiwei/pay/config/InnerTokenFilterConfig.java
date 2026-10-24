package com.zhiwei.pay.config;

import com.zhiwei.pay.third.CommonPayProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 {@link InnerTokenFilter}。
 * <p>
 * 用 {@code FilterRegistrationBean} 显式注册，而不是给过滤器加 {@code @Component}：
 * {@code @Component} 的 Filter 会被 Boot 自动注册到 {@code /*}，再叠一个 RegistrationBean
 * 就会<b>执行两次</b>（虽然校验幂等不会出错，但日志和性能都是浪费），且顺序不可控。
 * </p>
 * <p>
 * order 取 {@code HIGHEST_PRECEDENCE + 10}：要抢在所有业务处理之前把非法请求挡掉，
 * 但不与编码过滤器等基础设施争第一位（本过滤器只读 URI 和请求头，不涉及编解码）。
 * </p>
 */
@Configuration
public class InnerTokenFilterConfig {

    @Bean
    public FilterRegistrationBean<InnerTokenFilter> innerTokenFilterRegistration(CommonPayProperties payProperties) {
        FilterRegistrationBean<InnerTokenFilter> registration =
                new FilterRegistrationBean<>(new InnerTokenFilter(payProperties));
        registration.addUrlPatterns("/*");
        registration.setName("payInnerTokenFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
