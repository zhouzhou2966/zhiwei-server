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
}