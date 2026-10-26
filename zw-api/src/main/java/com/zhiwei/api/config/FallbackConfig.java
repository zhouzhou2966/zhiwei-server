package com.zhiwei.api.config;

import com.zhiwei.api.client.course.fallback.CatalogueClientFallback;
import com.zhiwei.api.client.course.fallback.CourseClientFallback;
import com.zhiwei.api.client.learning.fallback.LearningClientFallback;
import com.zhiwei.api.client.promotion.fallback.PromotionClientFallback;
import com.zhiwei.api.client.remark.fallback.RemarkClientFallback;
import com.zhiwei.api.client.trade.fallback.TradeClientFallback;
import com.zhiwei.api.client.user.fallback.UserClientFallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 降级工厂的注册处。
 *
 * <p>⚠️ 这里的 {@code @Bean} 不是可选的：Sentinel 的 {@code SentinelFeign.Builder.getFromContext}
 * 是「从容器里按类型取 fallbackFactory」，取不到直接抛
 * {@code IllegalStateException: No fallbackFactory instance of type ... found for feign client xxx}，
 * 导致整个 Spring 上下文启动失败。它和 Spring Cloud OpenFeign 默认的 FeignClientFactoryBean 不同
 * —— 后者找不到 bean 时会反射 new 一个，Sentinel 这条链路不会。
 *
 * <p>所以新增任何一个 {@code XxxClientFallback} 类，都必须在这里补一个 {@code @Bean}，
 * 否则只要该 client 被注入，服务就起不来。
 */
@Configuration
public class FallbackConfig {

    // course 是课程主数据唯一来源，learning 的 getNowLesson / queryMyLessons / queryMyPlans
    // 全部依赖它，必须有降级，否则 course 一挂学习中心整页 500。
    @Bean
    public CourseClientFallback courseClientFallback() {
        return new CourseClientFallback();
    }

    // catalogue 和 course 打同一个服务，必须成对降级：
    // 只降 course 的话，getNowLesson 后面查「学到第几节」还是会抛 RetryableException。
    @Bean
    public CatalogueClientFallback catalogueClientFallback() {
        return new CatalogueClientFallback();
    }

    @Bean
    public LearningClientFallback learningClientFallback() {
        return new LearningClientFallback();
    }

    @Bean
    public TradeClientFallback tradeClientFallback() {
        return new TradeClientFallback();
    }

    @Bean
    public UserClientFallback userClientFallback() {
        return new UserClientFallback();
    }

    @Bean
    public RemarkClientFallback remarkClientFallback() {
        return new RemarkClientFallback();
    }

    @Bean
    public PromotionClientFallback promotionClientFallback() {
        return new PromotionClientFallback();
    }


}


