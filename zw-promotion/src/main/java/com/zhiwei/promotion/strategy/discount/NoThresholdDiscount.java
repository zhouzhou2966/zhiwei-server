package com.zhiwei.promotion.strategy.discount;

import com.zhiwei.common.utils.NumberUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.promotion.domian.po.Coupon;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NoThresholdDiscount implements Discount {

    private static final String RULE_TEMPLATE = "无门槛抵{}元";

    // 无门槛优惠券，总金额大于优惠券金额即可使用
    @Override
    public boolean canUse(int totalAmount, Coupon coupon) {
        return totalAmount > coupon.getDiscountValue();
    }

    // 无门槛优惠券，直接返回优惠券金额
    @Override
    public int calculateDiscount(int totalAmount, Coupon coupon) {
        return coupon.getDiscountValue();
    }


    @Override
    public String getRule(Coupon coupon) {
        return StringUtils.format(RULE_TEMPLATE, NumberUtils.scaleToStr(coupon.getDiscountValue(), 2));
    }
}
