package com.zhiwei.promotion.handler;

import com.zhiwei.promotion.service.IUserCouponService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 错误消息重放任务（手动触发，不配周期调度）
 * <p>
 * 数据源是 {@code coupon:error:parked} 存档 —— 由 {@code PromotionErrorListener}
 * 在消费 error 队列时写入。平时保持为空，不需要调度；当告警日志
 * {@code [MQ-ERROR-ALERT]} 提示有消息重试耗尽、且失败原因（DB 抖动等）已排除后，
 * 在 XXL-JOB 控制台手动「执行一次」即可批量重放。
 * </p>
 * <p>
 * 重放是安全的：走的是和正常领券同一条「Lua 预扣 → 发 MQ」链路，
 * 消费侧幂等键 + DB 回查保证不会重复发券；券已结束/已领完的记录会被自动丢弃。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponErrorReplayJob {

    private final IUserCouponService userCouponService;

    @XxlJob("couponErrorReplayJob")
    public void couponErrorReplayTask() {
        try {
            int success = userCouponService.replayParkedErrors();
            XxlJobHelper.log("错误消息重放完成，本次成功 {} 条", success);
        } catch (Exception e) {
            log.error("couponErrorReplayJob failed", e);
            XxlJobHelper.handleFail("错误消息重放失败: " + e.getMessage());
            throw e;
        }
    }
}
