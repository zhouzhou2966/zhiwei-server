package com.zhiwei.promotion.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.promotion.domian.dto.CouponFormDTO;
import com.zhiwei.promotion.domian.dto.CouponIssueFormDTO;
import com.zhiwei.promotion.domian.po.Coupon;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.promotion.domian.query.CouponQuery;
import com.zhiwei.promotion.domian.vo.CouponDetailVO;
import com.zhiwei.promotion.domian.vo.CouponPageVO;
import com.zhiwei.promotion.domian.vo.CouponVO;

import javax.validation.Valid;
import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-26
 */
public interface ICouponService extends IService<Coupon> {
    // 保存优惠券
    void saveCoupon(@Valid CouponFormDTO dto);

    // 分页查询优惠券
    PageDTO<CouponPageVO> queryCouponPage(CouponQuery query);

    // 优惠券发放
    void issueCoupon(@Valid CouponIssueFormDTO dto);

    // 修改优惠券
    void updateCouponInfo(@Valid CouponFormDTO dto);

    // 查询优惠券详情
    CouponDetailVO getCouponById(Long id);

    // 删除优惠券
    void deleteCouponById(Long id);

    // 查询可发放的优惠券
    List<Coupon> queryCouponForIssue();

    // 暂停优惠券
    void pauseCoupon(Long id);

    // 查询正在发放的优惠券
    List<CouponVO> queryIssuingCoupons();

    /**
     * 把优惠券的发放信息写入 Redis 缓存，供领券 Lua 脚本读取。
     * <p>手动「立即发放」和 XXL-JOB 定时发放（UN_ISSUE → ISSUING）都必须调用，
     * 否则 Lua 第一步的 exists 判断会直接返回「优惠券不存在」。</p>
     *
     * @param coupon 优惠券模板（需含 id / 时间 / 数量 / 限领 / 获取方式）
     */
    void preheatCouponCache(Coupon coupon);

    /**
     * 清除优惠券的 Redis 缓存（发放结束或暂停时调用，避免 key 长期驻留）
     */
    void removeCouponCache(Long couponId);

}
