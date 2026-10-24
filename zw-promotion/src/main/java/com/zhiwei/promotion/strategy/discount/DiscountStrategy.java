package com.zhiwei.promotion.strategy.discount;

import com.zhiwei.promotion.enums.DiscountType;

import java.util.EnumMap;

public class DiscountStrategy {

    private final static EnumMap<DiscountType, Discount> strategies;// 优惠券策略映射

    static {
        strategies = new EnumMap<>(DiscountType.class);
        strategies.put(DiscountType.NO_THRESHOLD, new NoThresholdDiscount());//无门槛
        strategies.put(DiscountType.PER_PRICE_DISCOUNT, new PerPriceDiscount());// 满减
        strategies.put(DiscountType.RATE_DISCOUNT, new RateDiscount());// 打折
        strategies.put(DiscountType.PRICE_DISCOUNT, new PriceDiscount());// 满减
    }

    public static Discount getDiscount(DiscountType type) {
        return strategies.get(type);
    }
}
