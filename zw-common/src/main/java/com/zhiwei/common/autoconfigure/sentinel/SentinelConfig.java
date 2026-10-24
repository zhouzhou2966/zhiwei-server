package com.zhiwei.common.autoconfigure.sentinel;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sentinel 通用装配
 * <p>仅在引入了 sentinel-core 的 Servlet 应用中生效，网关（WebFlux）和其他未引入的服务不受影响。
 **/
@Configuration
@ConditionalOnClass(BlockException.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SentinelConfig {

    @Bean
    @ConditionalOnMissingBean
    public SentinelBlockExceptionAdvice sentinelBlockExceptionAdvice() {
        return new SentinelBlockExceptionAdvice();
    }
}
