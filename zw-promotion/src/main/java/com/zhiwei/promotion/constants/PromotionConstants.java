package com.zhiwei.promotion.constants;

public interface PromotionConstants {
    String COUPON_CODE_SERIAL_KEY = "coupon:code:serial";
    String COUPON_CODE_MAP_KEY = "coupon:code:map";
    String COUPON_CACHE_KEY_PREFIX = "prs:coupon:";
    String USER_COUPON_CACHE_KEY_PREFIX = "prs:user:coupon:";
    // 优惠券码最大值key
    String COUPON_CODE_RANGE_KEY = "coupon:code:range";


    String COUPON_ROLLBACK_KEY = "coupon:rollback";
    /**
     * 回滚失败的待补偿集合：MQ 发送回滚消息三次都失败时落这里，由 XXL-JOB 定时重试
     */
    String COUPON_ROLLBACK_PENDING_KEY = "coupon:rollback:pending";

    /**
     * 错误消息存档队列：进入 error 队列的领券消息由 PromotionErrorListener 存档到这里，
     * 由 couponErrorReplayJob 手动触发重放（error 队列本身会被兜底消费者消费掉，不留消息）
     */
    String COUPON_ERROR_PARKED_KEY = "coupon:error:parked";

    /**
     * 领券 Lua 返回 1~6，分别对应下面 6 条错误信息
     */
    String[] RECEIVE_COUPON_ERROR_MSG = {
            "优惠券不存在",       // 1
            "优惠券已售罄",       // 2
            "优惠券已过期",       // 3
            "超出领取数量",       // 4
            "该券仅支持兑换码兑换", // 5
            "活动尚未开始",        // 6
            "该优惠券未在发放中"     // 7
    };
    String[] EXCHANGE_COUPON_ERROR_MSG = {
            "兑换码已经兑换过了",   // 返回 "1"
            "兑换码不存在",         // 返回 "2"
            "优惠券不存在",         // 返回 "3"
            "兑换码已过期",         // 返回 "4"
            "超出领取数量",         // 返回 "5"
            "优惠券已售罄",          // 返回 "6"
            "该优惠券未在发放中"      // 返回 "7"
    };
}