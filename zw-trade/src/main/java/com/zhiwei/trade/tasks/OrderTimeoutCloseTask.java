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
 * 订单超时自动关单任务。
 * <p>
 * 为什么用「XXL-JOB 扫表」而不是延迟消息：
 * <ul>
 *   <li>本项目已有 rabbitmq-delayed-message 插件（trade 下单后靠它发 5s~120s 的延迟查单消息），
 *       但该插件把延迟消息存在单个 broker 的 Mnesia 表里，<b>不复制、不 HA</b>，
 *       broker 重启后仍在延迟期的消息会全部丢失。30 分钟的延迟消息意味着要在 Mnesia 里躺 30 分钟，
 *       这期间重启一次，订单就永远停在待支付 —— 正好是我们要修的那个 bug。
 *       用一个会丢消息的机制去修「状态永远不更新」，方向就是错的。</li>
 *   <li>扫表以数据库为唯一真相：任何一个实例挂了、任何一轮调度失败，下一轮都能补上，天然幂等自愈。</li>
 *   <li>30 分钟的支付窗口对「晚 30~60 秒关单」完全不敏感 —— 超时后用户点「去支付」本来就会被
 *       {@code PayServiceImpl#applyPayOrder} 拦下并提示「订单已超时」，秒级精度在这里没有业务价值。</li>
 * </ul>
 * </p>
 * <p>
 * 分片写法与 pay 侧的 {@code PayOrderCheckTask} 保持一致：按 {@code MOD(id, 分片总数) = 分片号} 切分，
 * 而不是把分片号当页码用（页码会随数据增删漂移，导致各分片重复处理或漏处理）。
 * </p>
 * <p>
 * <b>为什么一轮内要循环取而不是只取一批：</b>
 * 单批 200 条实测只花 5 秒左右，60 秒的调度周期里 55 秒是空闲的，吞吐上限完全是人为设的。
 * 一旦 pay 侧挂过一段时间攒下几万条积压，200 条/轮要跑好几个小时才清得完。
 * 改成「按时间预算循环取，直到取空或预算耗尽」，稳态下第一轮就取空、开销和原来一样，
 * 有积压时能在一轮里连续消化上千条，积压自动收敛。
 * </p>
 * <p>
 * <b>为什么循环要靠游标推进而不是靠「处理完就消失」：</b>
 * 关不掉的单（pay 暂时关不了渠道单、并发下状态刚被改）会一直留在 {@code status = 1}，
 * 而查询是 {@code ORDER BY create_time ASC}，它们永远排在队头。只靠状态变化来推进的话，
 * 队头一旦攒下足够多关不掉的单，每批取到的都是同一批，整轮空转到预算耗尽。
 * 所以用 {@code (create_time, id)} 做 keyset 分页，每批无条件把游标推到最后一条，
 * 保证一轮内严格向前、必然终止，同时这些单下一轮还会从队头被重试。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutCloseTask {

    /**
     * 连续失败多少次就提前结束本轮。
     * <p>
     * 关单要同步调 pay（Feign readTimeout 配的是 1500ms），pay 挂掉时每一条都要等超时：
     * 一批 200 条 × 1.5s ≈ 5 分钟，会把整轮任务拖死，还会让后续正常订单排不上。
     * 连续 5 条都失败基本可以断定是下游不可用，直接结束本轮、等下一轮，让调度中心的失败记录暴露问题。
     * </p>
     */
    private static final int MAX_CONSECUTIVE_FAIL = 5;

    private final IOrderService orderService;
    private final TradeProperties tradeProperties;

    @XxlJob("orderTimeoutCloseHandler")
    public void closeTimeoutOrders() {
        // 1.分片信息、批量大小、时间预算
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        int batchSize = resolveBatchSize(XxlJobHelper.getJobParam());
        long budgetMillis = tradeProperties.getCloseTaskBudgetSeconds() * 1000L;
        long startAt = System.currentTimeMillis();

        // 2.算出超时时间线。与 queryOrderStatus 里给前端算倒计时用的是同一个配置，
        //   保证「前端倒计时结束」和「后端真正关单」是同一个时间点，不会出现倒计时还在走、订单已经关了。
        //   整轮复用同一个 deadline（不在循环里重算）：中途刚超时的单留给下一轮，
        //   否则查询边界一直在往前漂，可能永远扫不到队尾。
        LocalDateTime deadline = LocalDateTime.now()
                .minusMinutes(tradeProperties.getPayOrderTTLMinutes());

        int scanned = 0, closed = 0, skipped = 0, failed = 0;
        int consecutiveFail = 0;
        boolean aborted = false;
        Order cursor = null;

        // 3.按时间预算循环拉取，用游标一批批往前推。
        //   处理完的订单会被改成「已关闭」，本来就会退出查询结果，但关不掉的单会留在原地 ——
        //   只靠「处理完就消失」来推进，遇到队头有 200 条关不掉的单就会原地打转，
        //   所以必须显式推进游标，让这一轮越过它们，下一轮再从队头重试。
        while (true) {
            List<Order> list = orderService.queryTimeoutNoPayOrders(
                    deadline, cursor, shardIndex, shardTotal, batchSize);
            if (CollUtils.isEmpty(list)) {
                break;
            }
            scanned += list.size();
            int closedInBatch = 0;
            boolean budgetExhausted = false;

            for (Order order : list) {
                // 预算检查放在循环体内而不是每批之后：pay 变慢时单批就可能跑几分钟（200 × 1.4s），
                // 只在批末检查会直接冲过 60 秒的调度周期，和下一轮撞上。
                if (System.currentTimeMillis() - startAt >= budgetMillis) {
                    budgetExhausted = true;
                    break;
                }
                try {
                    boolean ok = orderService.closeOrderInternal(order, "订单超时未支付，系统自动关闭");
                    if (ok) {
                        closed++;
                        closedInBatch++;
                    } else {
                        // 跳过是正常情况：并发下用户刚好付款了、或者 pay 侧暂时关不掉渠道单
                        skipped++;
                    }
                    consecutiveFail = 0;
                } catch (Exception e) {
                    failed++;
                    consecutiveFail++;
                    log.error("关闭超时订单{}失败", order.getId(), e);
                    if (consecutiveFail >= MAX_CONSECUTIVE_FAIL) {
                        log.error("连续{}笔关单失败，本轮提前结束（大概率是 pay 服务不可用）", consecutiveFail);
                        aborted = true;
                        break;
                    }
                }
            }

            if (aborted || budgetExhausted) {
                break;
            }
            // 整批一条都没关成功，说明 pay 侧对这批单都关不掉。不中断循环（游标会越过它们），
            // 但要打出来 —— 连续几轮都这样就是 pay 出问题了，别只看成功数
            if (closedInBatch == 0) {
                log.warn("本批{}条超时订单无一关闭成功，队头可能被阻塞，已越过继续处理后面的", list.size());
            }
            // 游标推进到本批最后一条。中途 break 时不需要推进，因为循环已经结束
            cursor = list.get(list.size() - 1);
            // 不满一批说明已经取到队尾，没有更多积压了
            if (list.size() < batchSize) {
                break;
            }
        }

        // 4.把结果写进 XXL-JOB 执行日志，方便在调度中心直接看到每轮关了多少
        String summary = String.format("超时关单完成：扫描%d条，关闭%d条，跳过%d条，失败%d条，耗时%dms",
                scanned, closed, skipped, failed, System.currentTimeMillis() - startAt);
        log.info(summary);
        XxlJobHelper.log(summary);
        // 有失败就让这次执行在调度中心标红：原先只在应用日志里 log.error，
        // 调度中心一律显示成功，关单失败很久都发现不了。
        if (failed > 0) {
            XxlJobHelper.handleFail(String.format("本轮有%d笔关单失败，详见执行日志", failed));
        }
    }

    /**
     * 任务参数填纯数字时按条数处理，否则用配置里的默认值。
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
