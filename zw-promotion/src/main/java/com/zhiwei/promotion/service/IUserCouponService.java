package com.zhiwei.promotion.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.promotion.domian.UserCouponDTO;
import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.domian.po.UserCoupon;
import com.zhiwei.promotion.domian.query.UserCouponQuery;
import com.zhiwei.promotion.domian.vo.CouponVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-29
 */
public interface IUserCouponService extends IService<UserCoupon> {
    // 领取优惠券
    void receiveCoupon(Long couponId);

    // 兑换优惠券
    void exchangeCoupon(String code);

    // 分页查询我的优惠券（按状态过滤：未使用/已使用/已过期）
    PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query);

    void checkAndCreateUserCoupon(UserCouponDTO userCouponDTO, String messageId);

    // 核销优惠券
    void writeOffCoupon(List<Long> couponIds);

    // 退还优惠券
    void refundCoupon(List<Long> couponIds);

    // 查询优惠卷规则
    List<String> queryCouponRules(List<Long> couponIds);

    // 回滚Redis中的领取记录（MQ消费失败时调用）
    void rollbackCouponReceive(UserCouponDTO uc);

    /**
     * 把回滚失败的记录放进待补偿集合（Redis Set），由 couponRollbackRetryJob 定时重试
     */
    void addPendingRollback(UserCouponDTO uc);

    /**
     * 重试待补偿集合里的回滚记录，成功的从集合里移除
     *
     * @return 本次成功回滚的条数
     */
    int retryPendingRollback();

    /**
     * 重放错误消息存档（coupon:error:parked）里的领券消息。
     * <p>重放 = 重新走一遍「Lua 预扣 → 发 MQ」，消费侧的幂等键 + DB 回查保证重放安全。</p>
     *
     * @return 本次成功重放的条数
     */
    int replayParkedErrors();

    /**
     * 优惠券库存对账：比对 DB 发放数 vs user_coupon 记录数、Redis 缓存剩余库存 vs DB 推导值。
     * 只告警不修复 —— 修复方向（以哪边为准）需要人工判断。
     *
     * @return 发现漂移的券数量
     */
    int auditCouponInventory();

}