package com.zhiwei.search.config;

import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RestHighLevelClient 的手工装配。
 *
 * <p>为什么要自己写：Spring Boot 2.7 有 {@code ElasticsearchRestClientAutoConfiguration}
 * 里的 {@code RestHighLevelClient} 自动装配，Boot 3 把它删了 —— 因为 Spring Data
 * Elasticsearch 5.x 全面转向 8.x 的 {@code ElasticsearchClient}，而
 * {@code RestHighLevelClient} 属于 7.x 客户端，不再由 Boot 托管。</p>
 *
 * <p>但 Boot 3.5 仍然保留了低层的 {@code RestClientBuilder}（
 * {@code ElasticsearchRestClientAutoConfiguration}），它照样读
 * {@code spring.elasticsearch.uris} / {@code username} / {@code password}，
 * 所以这里只需要拿现成的 builder 包一层即可，不必重复解析配置。</p>
 *
 * <p>注意版本必须整条链路钉在 7.17.x：{@code elasticsearch-rest-client} 若被 Boot BOM
 * 拉到 8.x，{@code RestHighLevelClient} 会在运行期抛 NoSuchMethodError。</p>
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient(RestClientBuilder restClientBuilder) {
        return new RestHighLevelClient(restClientBuilder);
    }
}
