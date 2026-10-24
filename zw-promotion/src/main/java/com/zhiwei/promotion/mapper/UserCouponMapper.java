package com.zhiwei.promotion.mapper;

import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.domian.po.UserCoupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiwei.promotion.enums.UserCouponStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-29
 */
public interface UserCouponMapper extends BaseMapper<UserCoupon> {
    // 查询用户优惠券
    List<Coupon> queryMyCoupons(Long userId);


}