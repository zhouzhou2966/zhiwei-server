package com.zhiwei.common.autoconfigure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 业务指标埋点工具
 *
 * <p>封装 Micrometer 的三种常用计量器，并统一命名规范：
 * <ul>
 *     <li>计数 → {@code {metric}_total}，带 {@code result} 标签区分成功/失败</li>
 *     <li>耗时 → {@code {metric}_duration_seconds}</li>
 * </ul>
 * 命名遵循 Prometheus 约定（后缀带单位、累加型指标用 {@code _total}），
 * 这样在 Grafana 里能直接套用社区现成的图表模板。
 *
 * <p><b>为什么耗时用直方图而不是分位数</b>：
 * Micrometer 的 {@code publishPercentiles()} 会让客户端算好 P99 再上报，
 * 上报上去的是一组<b>已经算完的分位数</b>。Prometheus 拿到多个实例的 P99 时只能再做平均，
 * 而"平均 P99"在统计上是没有意义的（P99 不可跨维度聚合）。
 * 改用 {@code publishPercentileHistogram()} 上报的是<b>桶计数</b>，
 * Prometheus 用 {@code histogram_quantile()} 能在服务端正确地聚合出全局 P99。
 * 代价是每个接口多出一批时间序列，所以只在关键链路上开直方图。
 **/
public class MetricsHelper {

    public static final String TAG_RESULT = "result";
    public static final String TAG_REASON = "reason";
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";

    private final MeterRegistry registry;

    public MetricsHelper(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次成功
     */
    public void success(String metric, Map<String, String> tags) {
        counter(metric, tags, TAG_RESULT, RESULT_SUCCESS).increment();
    }

    /**
     * 记录一次失败。
     * <p>用异常类型而不是异常 message 作为 reason 标签：message 里常带动态数据（id、时间），
     * 会造成时间序列无限膨胀（高基数问题），而异常类型是有限枚举，既能定位问题又不炸存储。
     */
    public void failure(String metric, Throwable e, Map<String, String> tags) {
        counter(metric, tags, TAG_RESULT, RESULT_FAILURE, TAG_REASON, reasonOf(e)).increment();
    }

    /**
     * 记录一次耗时
     *
     * @param millis 耗时毫秒数
     */
    public void record(String metric, long millis, Map<String, String> tags) {
        Timer.builder(metric + "_duration_seconds")
                .tags(buildTags(tags, null))
                .publishPercentileHistogram()
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    private Counter counter(String metric, Map<String, String> tags, String... extraTags) {
        return Counter.builder(metric + "_total")
                .tags(buildTags(tags, extraTags))
                .register(registry);
    }

    private io.micrometer.core.instrument.Tags buildTags(Map<String, String> tags, String[] extraTags) {
        List<String> kv = new ArrayList<>();
        if (tags != null) {
            tags.forEach((k, v) -> {
                if (k != null && v != null) {
                    kv.add(k);
                    kv.add(v);
                }
            });
        }
        if (extraTags != null) {
            for (int i = 0; i + 1 < extraTags.length; i += 2) {
                kv.add(extraTags[i]);
                kv.add(extraTags[i + 1]);
            }
        }
        return io.micrometer.core.instrument.Tags.of(kv.toArray(new String[0]));
    }

    private String reasonOf(Throwable e) {
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }
}
