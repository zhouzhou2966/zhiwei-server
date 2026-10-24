package com.zhiwei.promotion.handler;

import com.zhiwei.common.autoconfigure.alert.WebhookAlertNotifier;
import com.zhiwei.promotion.service.IUserCouponService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 优惠券库存对账任务
 * <p>
 * 这是领券异步链路的最终防线：confirm 挡不住的极端窗口（历史静默丢消息、
 * 补偿与重放的边界竞态）造成的 Redis / DB 漂移，靠它定期暴露。
 * 比对两级：DB 内部（issue_num vs user_coupon 记录数）、Redis vs DB（缓存剩余 vs 总量-已发放）。
 * </p>
 * <p>
 * 只告警不修复：修复方向需要人工判断（以 DB 为准刷 Redis，还是查消息轨迹补记录），
 * 自动修反而可能把正确的一方改错。发现漂移时任务标记失败，
 * XXL-JOB 控制台会显示红色，配合日志关键字 {@code [COUPON-DRIFT]} 排查。
 * </p>
 * <p>
 * 调度建议：每天凌晨低峰一次全量对账即可（券表量级 ≤ 5000 行/券，全量开销很小）。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CouponInventoryAuditJob {

    private final IUserCouponService userCouponService;
    private final WebhookAlertNotifier alertNotifier;

    @XxlJob("couponInventoryAuditJob")
    public void couponInventoryAuditTask() {
        try {
            int drift = userCouponService.auditCouponInventory();
            XxlJobHelper.log("库存对账完成，发现漂移 {} 张券", drift);
            if (drift > 0) {
                // 有漂移就让任务变红，控制台一眼能看到，不必去翻日志
                XxlJobHelper.handleFail("发现 " + drift + " 张券库存漂移，日志关键字 [COUPON-DRIFT]");
                // 对账每天才跑一次，控制台标红也可能几天没人看 —— 推一条群告警兜底。
                // 节流 key 固定为 coupon-drift，同一天重复漂移不会刷屏
                alertNotifier.notify("coupon-drift", "【领券链路】库存对账发现 " + drift
                        + " 张券数据漂移（Redis/DB 两本账不一致），请查 promotion 日志关键字 [COUPON-DRIFT]");
            }
        } catch (Exception e) {
            log.error("couponInventoryAuditJob failed", e);
            XxlJobHelper.handleFail("库存对账失败: " + e.getMessage());
            // 任务本身失败（DB 连不上等）也要让人知道，否则对账静默失效、防线形同虚设
            alertNotifier.notify("audit-fail", "【领券链路】库存对账任务执行失败：" + e.getMessage()
                    + "，防线失效期间数据漂移无法被察觉，请尽快排查");
            throw e;
        }
    }
}
