package com.zhiwei.promotion.handler;

import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.enums.CouponStatus;
import com.zhiwei.promotion.service.ICouponService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class IssueCouponHandler {

    private static final int PAGE_SIZE = 100;

    private final ICouponService couponService;

    @XxlJob("issueCouponJob")
    public void issueCouponTask() {
        int index = XxlJobHelper.getShardIndex();
        int total = XxlJobHelper.getShardTotal();
        LocalDateTime now = LocalDateTime.now();

        log.info("issueCouponJob start, shard={}/{}, time={}",  index ,total  , now);

        try {
            Long lastId = null;
            int processed = 0;
            while (true) {
                List<Coupon> list = couponService.lambdaQuery()
                        .eq(Coupon::getStatus, CouponStatus.UN_ISSUE)
                        .le(Coupon::getIssueBeginTime, now)
                        .apply("id % {0} = {1}", total, index)
                        .gt(lastId != null, Coupon::getId, lastId)
                        .orderByAsc(Coupon::getId)
                        .last("LIMIT " + PAGE_SIZE)
                        .list();

                if (CollUtils.isEmpty(list)) break;
                List<Long> ids = list.stream().map(Coupon::getId).collect(Collectors.toList());

                // ★ 先预热 Redis 缓存，再把状态改成「发放中」。
                //   顺序不能反：领券 Lua 的第一步就是 exists prs:coupon:{id}，
                //   若先改状态，券会短暂出现在「可领列表」里但缓存还没写，用户点了就是「优惠券不存在」。
                //   先写缓存则相反：券还不可见（列表只查 ISSUING），缓存已就绪，切换瞬间即可领。
                for (Coupon coupon : list) {
                    couponService.preheatCouponCache(coupon);
                }

                couponService.lambdaUpdate()
                        .in(Coupon::getId, ids)
                        .set(Coupon::getStatus, CouponStatus.ISSUING)
                        .update();

                lastId = ids.get(ids.size() - 1);
                processed += ids.size();
            }

            log.info("issueCouponJob done, shard={}/{}, processed={}", index, total, processed);
        } catch (Exception e) {
            log.error("issueCouponJob failed, shard={}/{}", index, total, e);
            XxlJobHelper.handleFail("发放优惠券失败: " + e.getMessage());
            throw e;
        }
    }

    @XxlJob("expireCouponJob")
    public void expireCouponTask() {
        int index = XxlJobHelper.getShardIndex();
        int total = XxlJobHelper.getShardTotal();
        LocalDateTime now = LocalDateTime.now();

        log.info("expireCouponJob start, shard={}/{}, time={}", index, total, now);

        try {
            Long lastId = null;
            int processed = 0;

            while (true) {
                List<Coupon> list = couponService.lambdaQuery()
                        .eq(Coupon::getStatus, CouponStatus.ISSUING)
                        .le(Coupon::getIssueEndTime, now)
                        .apply("id % {0} = {1}", total, index)
                        .gt(lastId != null, Coupon::getId, lastId)
                        .orderByAsc(Coupon::getId)
                        .last("LIMIT " + PAGE_SIZE)
                        .list();

                if (CollUtils.isEmpty(list)) break;

                List<Long> ids = list.stream().map(Coupon::getId).collect(Collectors.toList());

                couponService.lambdaUpdate()
                        .in(Coupon::getId, ids)
                        .set(Coupon::getStatus, CouponStatus.FINISHED)
                        .update();

                // 发放结束：清掉 Redis 里的券缓存和用户领取计数，回收内存。
                // 已领到用户手里的券在 user_coupon 表里，不受影响。
                for (Long id : ids) {
                    couponService.removeCouponCache(id);
                }

                lastId = ids.get(ids.size() - 1);
                processed += ids.size();
            }

            log.info("expireCouponJob done, shard={}/{}, processed={}", index, total, processed);
        } catch (Exception e) {
            log.error("expireCouponJob failed, shard={}/{}", index, total, e);
            XxlJobHelper.handleFail("定时结束优惠券失败: " + e.getMessage());
            throw e;
        }
    }
}
