package com.zhiwei.promotion.controller;


import com.zhiwei.api.dto.promotion.CouponDiscountDTO;
import com.zhiwei.api.dto.promotion.OrderCourseDTO;
import com.zhiwei.api.dto.promotion.OrderCouponDTO;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.promotion.domian.query.UserCouponQuery;
import com.zhiwei.promotion.domian.vo.CouponVO;
import com.zhiwei.promotion.service.IDiscountService;
import com.zhiwei.promotion.service.IUserCouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-29
 */
@Api(tags = "用户优惠券接口")
@RestController
@RequestMapping("/user-coupons")
@RequiredArgsConstructor
public class UserCouponController {
    private final IUserCouponService userCouponService;
    private final IDiscountService discountService;

    @ApiOperation("领取优惠券")
    @PostMapping("/{couponId}/receive")
    public void receiveCoupon(@PathVariable("couponId") Long couponId) {
        userCouponService.receiveCoupon(couponId);
    }

    @ApiOperation("兑换优惠券")
    @PostMapping("/{code}/exchange")
    public void exchangeCoupon(@PathVariable("code") String code) {
        userCouponService.exchangeCoupon(code);
    }

    @ApiOperation("分页查询我的优惠券")
    @GetMapping("/page")
    public PageDTO<CouponVO> queryMyCouponPage(@Valid UserCouponQuery query) {
        return userCouponService.queryMyCouponPage(query);
    }


    @ApiOperation("查询我的优惠券优惠可用方案")
    @PostMapping("/available")
    public List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> orderCourses) {
        return discountService.findDiscountSolution(orderCourses);
    }

    @ApiOperation("根据优惠方案计算订单优惠明细")
    @PostMapping("/discount")
    public CouponDiscountDTO queryDiscountDetailByOrder(@RequestBody OrderCouponDTO orderCouponDTO) {
        return discountService.queryDiscountDetailByOrder(orderCouponDTO);
    }

    @ApiOperation("核销优惠卷")
    @PostMapping("/use")
    public void writeOffCoupon(@RequestParam List<Long> couponIds) {
        userCouponService.writeOffCoupon(couponIds);
    }


    @ApiOperation("退还优惠券")
    @PostMapping("/refund")
    public void refundCoupon(@RequestParam List<Long> couponIds) {
        userCouponService.refundCoupon(couponIds);
    }

    @ApiOperation("查询优惠券规则")
    @GetMapping("/rules")
    public List<String> queryCouponRules(@RequestParam List<Long> couponIds) {
        return userCouponService.queryCouponRules(couponIds);
    }


}