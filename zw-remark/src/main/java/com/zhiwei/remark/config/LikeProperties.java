package com.zhiwei.remark.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 点赞相关配置。
 *
 * <p>不需要 {@code @RefreshScope}：Spring Cloud 的 {@code ConfigurationPropertiesRebinder}
 * 监听到 Nacos 变更后会把所有 {@code @ConfigurationProperties} bean 按新值重新绑定，
 * 加了只是多一层 scoped 代理，刷新能力一点不多给。
 * 前提是使用方每次通过 getter 读取，而不是启动时把值抄进自己的字段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "zw.remark.like")
public class LikeProperties {
    private List<String> bizTypes;
    private int maxBizSize = 30;
    /** 全量同步的最小点赞数阈值：点赞数低于该值的内容跳过对账（0=不跳过，全量对账） */
    private int fullSyncMinLikes = 0;
}