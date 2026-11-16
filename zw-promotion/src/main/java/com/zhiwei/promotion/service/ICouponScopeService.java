package com.zhiwei.promotion.service;

import com.zhiwei.promotion.domian.po.CouponScope;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 优惠券作用范围信息 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-26
 */
public interface ICouponScopeService extends IService<CouponScope> {

    /**
     * 批量查询多张券限定的分类id。
     * <p>发券列表和我的券列表都要做同一件事：把限定了范围的券挑出来，一次查完再分组，
     * 避免每张券查一次造成 N+1。逻辑收在这里，两处不必各写一遍。</p>
     *
     * @param couponIds 券id集合，可为空
     * @return couponId -> 分类id列表（只含 type=1 的分类范围；不限范围的券不在 map 里）
     */
    Map<Long, List<Long>> mapCategoryIdsByCouponIds(Collection<Long> couponIds);
}
