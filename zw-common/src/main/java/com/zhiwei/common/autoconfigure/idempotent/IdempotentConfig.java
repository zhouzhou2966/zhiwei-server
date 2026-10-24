package com.zhiwei.common.autoconfigure.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhiwei.common.autoconfigure.idempotent.aspect.IdempotentAspect;
import com.zhiwei.common.autoconfigure.idempotent.storage.IdempotentStorage;
import com.zhiwei.common.autoconfigure.idempotent.storage.RedisIdempotentStorage;
import com.zhiwei.common.autoconfigure.idempotent.token.IdempotentTokenController;
import com.zhiwei.common.autoconfigure.idempotent.token.IdempotentTokenService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 幂等框架自动装配
 * <p>接入成本：引入 zw-common 即可，方法上加 {@code @Idempotent} 生效。
 * 所有 Bean 都带 {@code @ConditionalOnMissingBean}，业务方想换存储实现
 * （例如换成 Caffeine 单机版）只要自己声明一个 {@link IdempotentStorage} 即可覆盖。
 **/
@Slf4j
@Configuration
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnProperty(prefix = "zw.idempotent", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotentProperties.class)
public class IdempotentConfig {

    @Bean
    @ConditionalOnMissingBean
    public IdempotentStorage idempotentStorage(RedissonClient redissonClient) {
        log.debug("初始化幂等存储：RedisIdempotentStorage");
        return new RedisIdempotentStorage(redissonClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentTokenService idempotentTokenService(IdempotentStorage idempotentStorage,
                                                         IdempotentProperties idempotentProperties) {
        return new IdempotentTokenService(idempotentStorage, idempotentProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(IdempotentStorage idempotentStorage,
                                             IdempotentProperties idempotentProperties,
                                             IdempotentTokenService idempotentTokenService,
                                             ObjectProvider<ObjectMapper> objectMapperProvider) {
        // 优先复用 Spring MVC 的 ObjectMapper，保证幂等缓存里的时间格式与接口返回一致；
        // 非 Web 场景（例如纯定时任务应用）取不到时退化为自建实例
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(IdempotentConfig::defaultObjectMapper);
        return new IdempotentAspect(idempotentStorage, idempotentProperties, idempotentTokenService, objectMapper);
    }

    private static ObjectMapper defaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * 令牌签发接口仅在 Servlet Web 应用中装配，网关（WebFlux）不会暴露它
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public IdempotentTokenController idempotentTokenController(IdempotentTokenService idempotentTokenService) {
        return new IdempotentTokenController(idempotentTokenService);
    }
}
