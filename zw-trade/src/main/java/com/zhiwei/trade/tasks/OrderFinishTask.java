package com.zhiwei.trade.tasks;

import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.trade.config.TradeProperties;
import com.zhiwei.trade.domain.po.Order;
import com.zhiwei.trade.service.IOrderService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单售后期结束自动转「已完成」任务。
 * <p>
 * <b>为什么要有这个任务：</b>
 * {@code OrderStatus.FINISHED(4, "已完成")} 定义了枚举、订单详情页也拼了「交易完成」
 * 这个进度节点，但<b>全项目没有任何一处把订单状态写成 4</b>，
 * 导致前端「已完成」这个筛选标签永远是空列表、{@code order.finish_time} 永远是 null。
 * </p>
 * <p>
 * <b>为什么是「支付后 N 小时」而不是「课程学完」：</b>
 * 「学没学完」属于学习维度，learning 侧已经有 {@code learning_lesson.status}
 * （{@code LessonStatus.FINISHED}）在表达，跟订单是两个维度。
 * 订单的「已完成」是交易维度的终态：售后期已过、不能再退款，交易闭环。
 * 这也正是 {@code Order#finishTime} 字段注释「订单完成时间，支付后30天」的原意，
 * 只是当年没实现。时长做成配置（默认 168 小时 = 7 天），见 {@link TradeProperties#getOrderFinishHours()}。
 * </p>
 * <p>
 * <b>为什么用扫表而不是延迟消息：</b>
 * 与 {@link OrderTimeoutCloseTask} 同一个理由，且这里的延迟更长（7 天 vs 30 分钟）：
 * rabbitmq-delayed-message 把延迟消息存在单个 broker 的 Mnesia 表里，不复制、不 HA，
 * broker 重启后仍在延迟期的消息全部丢失。让一条消息在 broker 里躺 7 天，
 * 期间任何一次重启都会导致那批订单永远停在「已支付」。
 * 扫表以数据库为唯一真相，任务挂了、漏跑了，下一轮自动补上，天然幂等。
 * </p>
 * <p>
 * <b>调度频率可以很低：</b>
 * 建议每小时一次即可。完成时间用的是 {@code pay_time + N 小时} 而不是 {@code now()}，
 * 所以任务跑得粗、跑晚了，落库的完成时间依然是准确的那个时刻，不会随执行时机漂移。
 * 用户对这个状态也没有实时感知，不需要像超时关单那样每分钟跑。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFinishTask {

    /**
     * 单轮最多处理多少批，防止异常情况下无限循环。
     * <p>
     * 与超时关单不同，这里没有下游调用、单条只做两条 UPDATE，速度是毫秒级，
     * 所以不用按时间预算控制，用一个足够大的批次数上限兜底即可：
     * 200 批 × 200 条 = 单轮最多消化 4 万条，正常一轮就取空了。
     * </p>
     */
    private static final int MAX_BATCHES = 200;

    private final IOrderService orderService;
    private final TradeProperties tradeProperties;

    @XxlJob("orderFinishHandler")
    public void finishPaidOrders() {
        // 1.分片信息、批量大小
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        int batchSize = resolveBatchSize(XxlJobHelper.getJobParam());
        long startAt = System.currentTimeMillis();

        // 2.算出售后期的时间线。整轮复用同一个 deadline（不在循环里重算）：
        //   否则查询边界一直在往前漂，可能永远扫不到队尾。
        LocalDateTime deadline = LocalDateTime.now()
                .minusHours(tradeProperties.getOrderFinishHours());

        int scanned = 0, finished = 0, skipped = 0, failed = 0;
        Order cursor = null;

        // 3.用游标一批批往前推。
        //   处理成功的订单会变成「已完成」自动退出结果集，但被跳过的（恰好被退款、
        //   或别的实例刚处理完）仍会留在 status=2，只靠「处理完就消失」会原地打转，
        //   所以必须显式推进游标，保证一轮内严格向前、必然终止。
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<Order> list = orderService.queryFinishableOrders(
                    deadline, cursor, shardIndex, shardTotal, batchSize);
            if (CollUtils.isEmpty(list)) {
                break;
            }
            scanned += list.size();
            for (Order order : list) {
                try {
                    if (orderService.finishOrderInternal(order)) {
                        finished++;
                    } else {
                        // 跳过是正常情况：并发下用户刚申请了退款，或别的实例已处理
                        skipped++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("订单{}自动完成失败", order.getId(), e);
                }
            }
            // 游标推进到本批最后一条
            cursor = list.get(list.size() - 1);
            // 不满一批说明已经取到队尾
            if (list.size() < batchSize) {
                break;
            }
        }

        // 4.把结果写进 XXL-JOB 执行日志，方便在调度中心直接看到每轮处理了多少
        String summary = String.format(
                "订单自动完成：扫描%d条，完成%d条，跳过%d条，失败%d条，耗时%dms",
                scanned, finished, skipped, failed, System.currentTimeMillis() - startAt);
        log.info(summary);
        XxlJobHelper.log(summary);
        // 有失败就在调度中心标红，避免只看应用日志才发现问题
        if (failed > 0) {
            XxlJobHelper.handleFail(String.format("本轮有%d笔订单完成失败，详见执行日志", failed));
        }
    }

    /**
     * 任务参数填纯数字时按条数处理，否则用配置里的默认值。
     * <p>
     * 复用超时关单的批量配置：两者都是「扫表批处理」性质，没必要为这个任务单开一个配置项。
     * </p>
     */
    private int resolveBatchSize(String jobParam) {
        if (StringUtils.isNumeric(jobParam)) {
            int size = Integer.parseInt(jobParam);
            if (size > 0) {
                return size;
            }
        }
        return tradeProperties.getCloseTaskBatchSize();
    }
}
