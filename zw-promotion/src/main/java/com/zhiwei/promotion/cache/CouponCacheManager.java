package com.zhiwei.promotion.cache;

import com.zhiwei.common.utils.DateUtils;
import com.zhiwei.common.utils.NumberUtils;
import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.enums.ObtainType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static com.zhiwei.promotion.constants.PromotionConstants.COUPON_CACHE_KEY_PREFIX;
import static com.zhiwei.promotion.constants.PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX;

/**
 * 优惠券库存缓存的读写入口。
 * <p>
 * 存在的意义不只是「把 Redis 操作集中一下」：领券链路在缓存缺失时需要回源 DB 重建缓存，
 * 而 {@code CouponServiceImpl} 已经注入了 {@code IUserCouponService}，如果
 * {@code UserCouponServiceImpl} 再反向注入 {@code ICouponService}，两者都是
 * {@code @RequiredArgsConstructor} 构造器注入，Boot 3 默认禁止循环引用，会直接启动失败。
 * 把缓存写入下沉到这个只依赖 {@code StringRedisTemplate} 的组件，两边都能安全注入。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponCacheManager {

    private final StringRedisTemplate redisTemplate;

    /**
     * 缓存重建锁前缀。
     * <p>重建是「覆盖写」而不是「原子递减」：多个请求同时发现缓存缺失时，
     * 若都执行 preheat，后写的那个会用自己读到的 issueNum 覆盖前一个，
     * 把并发期间真实发生的领券量抹掉，等于凭空多放出库存。加锁后降级为串行重建。</p>
     */
    private static final String REBUILD_LOCK_PREFIX = "prs:coupon:rebuild:";

    /**
     * 重建锁有效期。临界区只有一次 selectById + 一次 HSET，正常在毫秒级完成；
     * 取 3 秒是为了在进程被 kill 时自动解锁，不会把这张券永久锁死。
     */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(3);

    /**
     * 已过期券的缓存兜底 TTL。
     * <p>券已经过了发放结束时间（+7 天）却还在「发放中」时，正常 TTL 算出来是负数。
     * 这类缓存唯一的作用是让用户拿到「优惠券已过期」而不是「优惠券不存在」，
     * 给一小时足够，过期后继续由 expireCouponJob 收尾。</p>
     */
    private static final Duration EXPIRED_COUPON_TTL = Duration.ofHours(1);

    /**
     * 预热券库存缓存。
     * <p>两个关键点：</p>
     * <ul>
     *   <li>库存写的是<b>剩余可领数量</b>（totalNum - issueNum），不是总量。
     *       否则「暂停后重新发放」时 Redis 会多放出已发出去的那部分，DB 侧
     *       {@code issue_num < total_num} 会拦住，用户看到的是「已发放完毕」而不是成功。</li>
     *   <li>写入 obtainWay，让 Lua 能拒绝「兑换码专属券走手动领取接口」。</li>
     * </ul>
     */
    public void preheat(Coupon coupon) {
        if (coupon == null || coupon.getId() == null) return;
        int totalNum = NumberUtils.null2Zero(coupon.getTotalNum());
        int issueNum = NumberUtils.null2Zero(coupon.getIssueNum());
        int remain = Math.max(totalNum - issueNum, 0);
        int userLimit = NumberUtils.null2Zero(coupon.getUserLimit());
        // 限额必须 >= 1。配成 0 或 null 时 Lua 里 `curCount >= 0` 恒真，这张券会永远领不了，
        // 而且报的是「超出领取数量」—— 运营完全看不出是配置问题。
        if (userLimit <= 0) userLimit = 1;
        int obtainWay = coupon.getObtainWay() == null
                ? ObtainType.PUBLIC.getValue() : coupon.getObtainWay().getValue();

        // 时间为 null 时 toEpochMilli 返回 null，String.valueOf 得到字符串 "null"，
        // Lua 侧 tonumber("null") 得 nil，`if (issueEndTime and ...)` 直接跳过校验 = 不限时间。
        // 这里千万不能把 null 兜底成 0：结束时间写 0 会让 Lua 判成「已过期」（nowMs > 0），
        // 无结束时间的券会直接领不了。
        Map<String, String> map = new HashMap<>(8);
        map.put("issueBeginTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueBeginTime())));
        map.put("issueEndTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueEndTime())));
        map.put("totalNum", String.valueOf(remain));
        map.put("userLimit", String.valueOf(userLimit));
        map.put("obtainWay", String.valueOf(obtainWay));

        String key = COUPON_CACHE_KEY_PREFIX + coupon.getId();
        redisTemplate.opsForHash().putAll(key, map);
        // TTL 取「发放结束时间 + 7 天」：既保证发放期内计数不会提前过期，
        // 又能在活动结束后自动回收，避免 key 永久驻留。
        //
        // issueEndTime 为 null（长期有效券）时不设过期，保持常驻。
        // 但如果 issueEndTime 已经过去很久（expireCouponJob 没跑到，券卡在「发放中」），
        // 「结束时间 + 7 天」算出来是负数，早期实现会直接跳过 expire，
        // 结果这张早已过期的券反而留下一个永久 key。这里退化成短 TTL：
        // 缓存只需要撑到把「已过期」这个准确错误码返回给用户，没必要长期驻留。
        if (coupon.getIssueEndTime() != null) {
            long seconds = Duration.between(LocalDateTime.now(), coupon.getIssueEndTime().plusDays(7)).getSeconds();
            redisTemplate.expire(key, seconds > 0 ? Duration.ofSeconds(seconds) : EXPIRED_COUPON_TTL);
        }
    }

    /**
     * 清空某张券的库存缓存与用户领取计数。
     * <p>只用于「发放结束」这种明确回收的场景；暂停发放只删券缓存（见
     * {@code CouponServiceImpl#pauseCoupon}），因为用户计数还要为恢复发放保留。</p>
     */
    public void evict(Long couponId) {
        if (couponId == null) return;
        redisTemplate.delete(COUPON_CACHE_KEY_PREFIX + couponId);
        redisTemplate.delete(USER_COUPON_CACHE_KEY_PREFIX + couponId);
    }

    /**
     * 只清券库存缓存，保留用户领取计数。
     * <p>用于「暂停发放」：暂停期间不能再领，但恢复发放后限领额度必须还在，
     * 否则用户会凭空多出一次领取机会。</p>
     */
    public void evictCouponOnly(Long couponId) {
        if (couponId == null) return;
        redisTemplate.delete(COUPON_CACHE_KEY_PREFIX + couponId);
    }

    /**
     * 尝试获取重建锁。
     *
     * @return true 表示当前线程负责重建，false 表示别的线程正在重建
     */
    public boolean tryLockRebuild(Long couponId) {
        if (couponId == null) return false;
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(REBUILD_LOCK_PREFIX + couponId, "1", REBUILD_LOCK_TTL);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 释放重建锁。临界区极短（远小于 TTL），因此不做 token 校验：
     * 不存在「自己的锁已过期、却误删了别人的锁」的时间窗口。
     */
    public void releaseRebuildLock(Long couponId) {
        if (couponId == null) return;
        redisTemplate.delete(REBUILD_LOCK_PREFIX + couponId);
    }
}
