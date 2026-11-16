package com.zhiwei.promotion.domian;

import lombok.Data;

@Data
public class UserCouponDTO {
    /**
     * 用户id
     */
    private Long userId;
    /**
     * 优惠券id
     */
    private Long couponId;
    /**
     * 兑换码id
     */
    private Long codeId;
    /**
     * 领取请求唯一标识（幂等键）。
     * <p>普通领取用 UUID，兑换码用 "exchange:{序列号}" —— 同一张码被重投时值不变，
     * 才能既挡住重复消费，又不影响同一用户合法领取第二张。</p>
     */
    private String requestId;
}