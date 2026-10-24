package com.zhiwei.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "zw.trade")
public class TradeProperties {
    /**
     * 单次允许购买的最大课程数量
     */
    private int maxCourseAmount = 10;
    /**
     * 订单支付的最大等待时间，单位分钟
     */
    private int payOrderTTLMinutes = 30;

    /**
     * 订单在支付成功后多少小时自动转为「已完成」，单位小时。
     * <p>
     * 语义是<b>售后期</b>：这段时间内订单停留在「已支付」，{@code canRefund} 为 true，用户可以申请退款；
     * 过了这个窗口订单转成终态「已完成」，同时不再允许退款（{@code OrderStatus#canRefund} 只认「已支付」）。
     * 所以这个值等于「用户能退款的最长时长」，改大改小直接影响售后政策，不只是个展示字段。
     * </p>
     * <p>
     * <b>为什么用小时而不是天：</b>业务上是「7 天售后期」，但测试和临时调整时按天粒度太粗 ——
     * 想验证链路就得等好几天，或者去改数据库的 pay_time。用小时表达，7 天 = 168 小时，
     * 想快速验证就配 3，不用动代码也不用改数据。默认 168（= 7 天），
     * 可在 Nacos 的 trade-service.yaml 里用 {@code zw.trade.order-finish-hours} 覆盖，不需要重新打包。
     * </p>
     * <p>
     * {@code Order#finishTime} 的字段注释写的是「支付后30天」，但该逻辑从未实现。
     * </p>
     */
    private int orderFinishHours = 168;

    /**
     * 超时关单任务单批拉取条数。
     * <p>
     * 可以在 XXL-JOB 的任务参数里临时覆盖（参数填纯数字即视为条数），改这里只影响默认值。
     * </p>
     */
    private int closeTaskBatchSize = 200;

    /**
     * 超时关单任务单轮的时间预算，单位秒。
     * <p>
     * 必须明显小于调度周期（60 秒）：任务配置的是 SERIAL_EXECUTION 串行执行，
     * 单轮跑过 60 秒会让下一轮排队等待，堆积起来就是「看着在跑、实际越跑越慢」。
     * 留 30 秒预算，正常情况下足够处理约 1000 条。
     * </p>
     */
    private int closeTaskBudgetSeconds = 30;
}
