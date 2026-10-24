package com.zhiwei.promotion.handler;

import com.zhiwei.common.autoconfigure.alert.WebhookAlertNotifier;
import com.zhiwei.promotion.constants.PromotionConstants;
import com.zhiwei.promotion.service.IUserCouponService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 领券回滚补偿任务
 * <p>
 * 领券是「Redis 先扣、MQ 异步落库」的模型，落库失败时必须把 Redis 里已扣的库存和用户计数还回去。
 * 回滚本身走 Redis，也会失败（Redis 抖动、网络分区）。失败三次就落进
 * {@code coupon:rollback:pending} 集合，由这个任务定时重试 —— 否则那部分库存就永久泄漏了，
 * 表现是「明明还剩券，用户却一直提示已领完」。
 * </p>
 * <p>
 * 告警：补偿后集合仍非空说明 Redis 持续异常或记录反复失败 —— 库存处于泄漏状态，
 * 超过节流窗口就推一条群告警，避免「券明明还有却一直领不了」的客诉先于告警到达。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponRollbackRetryHandler {

    private final IUserCouponService userCouponService;
    private final StringRedisTemplate redisTemplate;
    private final WebhookAlertNotifier alertNotifier;

    @XxlJob("couponRollbackRetryJob")
    public void couponRollbackRetryTask() {
        try {
            int success = userCouponService.retryPendingRollback();
            XxlJobHelper.log("回滚补偿完成，本次成功 {} 条", success);
            // 处理完一轮后集合里还剩的条数：0 = 全部消化，>0 = 有记录本轮重试仍失败
            Long pending = redisTemplate.opsForSet().size(PromotionConstants.COUPON_ROLLBACK_PENDING_KEY);
            if (pending != null && pending > 0) {
                XxlJobHelper.log("仍有 {} 条回滚记录待下次重试", pending);
                alertNotifier.notify("rollback-pending", "【领券链路】回滚补偿后仍有 " + pending
                        + " 条待回滚记录积压（coupon:rollback:pending），部分用户领券被扣的库存未返还，"
                        + "表现为提示已领完但券实际未入账，请排查 Redis 状态");
            }
        } catch (Exception e) {
            log.error("couponRollbackRetryJob failed", e);
            XxlJobHelper.handleFail("回滚补偿失败: " + e.getMessage());
            alertNotifier.notify("rollback-job-fail", "【领券链路】回滚补偿任务执行失败：" + e.getMessage());
            throw e;
        }
    }
}
