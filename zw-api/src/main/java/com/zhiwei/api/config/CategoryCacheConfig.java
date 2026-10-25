package com.zhiwei.api.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhiwei.api.cache.CategoryCache;
import com.zhiwei.api.client.course.CategoryClient;
import com.zhiwei.api.dto.course.CategoryBasicDTO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * 课程分类缓存配置。
 *
 * <p>{@code @Configuration} 不能省：Boot 2.7 走 {@code spring.factories} 时，
 * 列进去的类即使没有该注解也会被按 lite mode 解析；Boot 3 换成
 * {@code AutoConfiguration.imports} 后不再兜底，少了注解 {@code @Bean} 全部不生效，
 * 表现为注入处报 “required a bean of type CategoryCache that could not be found”。</p>
 */
@Configuration
public class CategoryCacheConfig {
    /**
     * 课程分类的caffeine缓存
     */
    @Bean
    public Cache<String, Map<Long, CategoryBasicDTO>> categoryCaches(){
        return Caffeine.newBuilder()
                .initialCapacity(1) // 容量限制
                .maximumSize(10_000) // 最大内存限制
                .expireAfterWrite(Duration.ofMinutes(30)) // 有效期
                .build();
    }
    /**
     * 课程分类的缓存工具类
     */
    @Bean
    public CategoryCache categoryCache(
            Cache<String, Map<Long, CategoryBasicDTO>> categoryCaches, CategoryClient categoryClient){
        return new CategoryCache(categoryCaches, categoryClient);
    }
}
