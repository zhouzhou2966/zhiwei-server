package com.zhiwei.promotion.service;

import com.zhiwei.api.dto.promotion.CouponDiscountDTO;
import com.zhiwei.api.dto.promotion.OrderCourseDTO;
import com.zhiwei.api.dto.promotion.OrderCouponDTO;

import java.util.List;

public interface IDiscountService {
    // 查询订单可用优惠券优惠方案方案
    List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses);
     // 根据优惠方案计算订单优惠明细
    CouponDiscountDTO queryDiscountDetailByOrder(OrderCouponDTO orderCouponDTO);
}
