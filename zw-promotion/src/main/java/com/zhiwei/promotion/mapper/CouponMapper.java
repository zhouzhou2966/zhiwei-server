package com.zhiwei.promotion.mapper;

import com.zhiwei.promotion.domian.po.Coupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiwei.promotion.enums.UserCouponStatus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-26
 */
public interface CouponMapper extends BaseMapper<Coupon> {
    @Update("update coupon set issue_num = issue_num + 1 where id = #{couponId} and issue_num < total_num")
    int increaseIssueNum(Long couponId);

    @Update("UPDATE coupon SET issue_num = issue_num - 1 WHERE id = #{couponId} AND issue_num > 0")
    int decreaseIssueNum(Long couponId);

    /**
     * 单张模板的已使用数 +1
     * <p>核销是按「券模板」传参的，一次可能涉及多张不同模板。
     * 原来的 {@link #increaseUsedNum(List, int)} 是一条 `UPDATE ... WHERE id IN (...) SET used_num = used_num + 1`，
     * 无论 IN 里有多少个 id 都只 +1，所以多券叠加时计数必然偏小。改为逐个模板调用。</p>
     */
    @Update("UPDATE coupon SET used_num = used_num + 1 WHERE id = #{couponId} AND used_num < issue_num")
    int increaseUsedNumByOne(@Param("couponId") Long couponId);

    /**
     * 单张模板的已使用数 -1（退款退还时用）
     */
    @Update("UPDATE coupon SET used_num = used_num - 1 WHERE id = #{couponId} AND used_num > 0")
    int decreaseUsedNumByOne(@Param("couponId") Long couponId);

    int increaseUsedNum(@Param("couponIds") List<Long> couponIds, @Param("num") int num);

    List<Coupon> queryCouponsByIds(@Param("couponIds") List<Long> couponIds, @Param("status") UserCouponStatus status);
}