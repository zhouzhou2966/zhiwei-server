package com.zhiwei.api.client.promotion;

import com.zhiwei.api.client.promotion.fallback.PromotionClientFallback;
import com.zhiwei.api.dto.promotion.CouponDiscountDTO;
import com.zhiwei.api.dto.promotion.OrderCouponDTO;
import com.zhiwei.api.dto.promotion.OrderCourseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;


@FeignClient(value = "promotion-service", fallbackFactory = PromotionClientFallback.class)
public interface PromotionClient {
    @PostMapping("/user-coupons/available")
    List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> orderCourses);


    @PostMapping("/user-coupons/discount")
    CouponDiscountDTO queryDiscountDetailByOrder(@RequestBody OrderCouponDTO orderCouponDTO);

    @PostMapping("/user-coupons/use")
    void writeOffCoupon(@RequestParam List<Long> couponIds);

    @PostMapping("/user-coupons/refund")
    void refundCoupon(@RequestParam List<Long> couponIds);

    @GetMapping("/user-coupons/rules")
    List<String> queryCouponRules(@RequestParam List<Long> couponIds);

}