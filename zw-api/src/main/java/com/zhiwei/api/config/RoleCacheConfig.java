package com.zhiwei.api.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhiwei.api.cache.RoleCache;
import com.zhiwei.api.client.auth.AuthClient;
import com.zhiwei.api.dto.auth.RoleDTO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 角色缓存配置。
 *
 * <p>同 {@link CategoryCacheConfig}：{@code @Configuration} 必须显式标注，
 * Boot 3 的 {@code AutoConfiguration.imports} 不会像 Boot 2 的
 * {@code spring.factories} 那样按 lite mode 兜底解析。</p>
 */
@Configuration
public class RoleCacheConfig {
    /**
     * 角色的caffeine缓存
     */
    @Bean
    public Cache<Long, RoleDTO> roleCaches(){
        return Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
    }
    /**
     * 角色的缓存工具
     */
    @Bean
    public RoleCache roleCache(Cache<Long, RoleDTO> roleCaches, AuthClient authClient){
        return new RoleCache(roleCaches, authClient);
    }
}
