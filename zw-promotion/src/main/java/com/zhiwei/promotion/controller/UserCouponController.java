package com.zhiwei.promotion.controller;


import com.zhiwei.api.dto.promotion.CouponDiscountDTO;
import com.zhiwei.api.dto.promotion.OrderCourseDTO;
import com.zhiwei.api.dto.promotion.OrderCouponDTO;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.promotion.domian.query.UserCouponQuery;
import com.zhiwei.promotion.domian.vo.CouponVO;
import com.zhiwei.promotion.domian.vo.ExchangePreviewVO;
import com.zhiwei.promotion.service.IDiscountService;
import com.zhiwei.promotion.service.IUserCouponService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-29
 */
@Tag(name = "用户优惠券接口")
@RestController
@RequestMapping("/user-coupons")
@RequiredArgsConstructor
public class UserCouponController {
    private final IUserCouponService userCouponService;
    private final IDiscountService discountService;

    @Operation(summary = "领取优惠券")
    @PostMapping("/{couponId}/receive")
    public void receiveCoupon(@PathVariable("couponId") Long couponId) {
        userCouponService.receiveCoupon(couponId);
    }

    @Operation(summary = "兑换优惠券")
    @PostMapping("/{code}/exchange")
    public void exchangeCoupon(@PathVariable("code") String code) {
        userCouponService.exchangeCoupon(code);
    }

    @Operation(summary = "兑换码预检：会换到什么券、能不能兑")
    @GetMapping("/{code}/preview")
    public ExchangePreviewVO previewExchange(@PathVariable("code") String code) {
        return userCouponService.previewExchange(code);
    }

    @Operation(summary = "分页查询我的优惠券")
    @GetMapping("/page")
    public PageDTO<CouponVO> queryMyCouponPage(@Valid UserCouponQuery query) {
        return userCouponService.queryMyCouponPage(query);
    }


    @Operation(summary = "查询我的优惠券优惠可用方案")
    @PostMapping("/available")
    public List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> orderCourses) {
        return discountService.findDiscountSolution(orderCourses);
    }

    @Operation(summary = "根据优惠方案计算订单优惠明细")
    @PostMapping("/discount")
    public CouponDiscountDTO queryDiscountDetailByOrder(@RequestBody OrderCouponDTO orderCouponDTO) {
        return discountService.queryDiscountDetailByOrder(orderCouponDTO);
    }

    @Operation(summary = "核销优惠卷")
    @PostMapping("/use")
    public void writeOffCoupon(@RequestParam List<Long> couponIds) {
        userCouponService.writeOffCoupon(couponIds);
    }


    @Operation(summary = "退还优惠券")
    @PostMapping("/refund")
    public void refundCoupon(@RequestParam List<Long> couponIds) {
        userCouponService.refundCoupon(couponIds);
    }

    @Operation(summary = "查询优惠券规则")
    @GetMapping("/rules")
    public List<String> queryCouponRules(@RequestParam List<Long> couponIds) {
        return userCouponService.queryCouponRules(couponIds);
    }


}