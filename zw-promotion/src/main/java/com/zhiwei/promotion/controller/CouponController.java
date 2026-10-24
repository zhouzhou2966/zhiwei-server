package com.zhiwei.promotion.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.promotion.domian.dto.CouponFormDTO;
import com.zhiwei.promotion.domian.dto.CouponIssueFormDTO;
import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.domian.query.CouponQuery;
import com.zhiwei.promotion.domian.vo.CouponDetailVO;
import com.zhiwei.promotion.domian.vo.CouponPageVO;
import com.zhiwei.promotion.domian.vo.CouponVO;
import com.zhiwei.promotion.service.ICouponService;
import com.zhiwei.promotion.service.impl.CouponServiceImpl;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-26
 */
@Tag(name = "优惠券接口")
@RequiredArgsConstructor
@RestController
@RequestMapping("/coupons")
public class CouponController {

    private final ICouponService couponService;

    @Operation(summary = "保存优惠券")
    @PostMapping
    public void saveCoupon(@RequestBody @Valid CouponFormDTO dto) {
        couponService.saveCoupon(dto);
    }

    @Operation(summary = "分页查询优惠券")
    @GetMapping("/page")
    public PageDTO<CouponPageVO> queryCouponPage(CouponQuery query) {
        return couponService.queryCouponPage(query);
    }

    @Operation(summary = "发放优惠券")
    @PutMapping("/{id}/issue")
    public void issueCoupon(@RequestBody @Valid CouponIssueFormDTO dto) {
        couponService.issueCoupon(dto);
    }

    @Operation(summary = "修改优惠券")
    @PutMapping("/{id}")
    public void updateCoupon(@RequestBody @Valid CouponFormDTO dto) {
        couponService.updateCouponInfo(dto);
    }

    @Operation(summary = "查询优惠券详情")
    @GetMapping("/{id}")
    public CouponDetailVO getCouponById(@PathVariable Long id) {
        return couponService.getCouponById(id);
    }


    @DeleteMapping("/{id}")
    public void deleteCouponById(@PathVariable Long id) {
        couponService.deleteCouponById(id);
    }

    @Operation(summary = "暂停优惠券")
    @PutMapping("/{id}/pause")
    public void pauseCoupon(@PathVariable Long id) {
        couponService.pauseCoupon(id);
    }


    @Operation(summary = "查询正在发放的优惠券")
    @GetMapping("/list")
    public List<CouponVO> queryIssuingCoupons() {
        return couponService.queryIssuingCoupons();
    }
}
