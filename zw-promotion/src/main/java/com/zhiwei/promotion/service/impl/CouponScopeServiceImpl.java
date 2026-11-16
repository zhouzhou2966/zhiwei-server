package com.zhiwei.promotion.service.impl;

import com.zhiwei.promotion.domian.po.CouponScope;
import com.zhiwei.promotion.mapper.CouponScopeMapper;
import com.zhiwei.promotion.service.ICouponScopeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 优惠券作用范围信息 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-26
 */
@Service
public class CouponScopeServiceImpl extends ServiceImpl<CouponScopeMapper, CouponScope> implements ICouponScopeService {

    /** 范围类型：1-分类（运营后台「指定课程分类」写的就是这个），2-课程（历史数据，仅极少数券存在） */
    private static final int TYPE_CATEGORY = 1;

    @Override
    public Map<Long, List<Long>> mapCategoryIdsByCouponIds(Collection<Long> couponIds) {
        if (couponIds == null || couponIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<CouponScope> scopes = lambdaQuery()
                .in(CouponScope::getCouponId, couponIds)
                .eq(CouponScope::getType, TYPE_CATEGORY)
                .list();
        if (scopes.isEmpty()) {
            return Collections.emptyMap();
        }
        // 只保留分类范围：算订单可用券时比的是课程的 cateId（三级分类），
        // type=2 的课程范围在这套逻辑里不参与匹配，透给前端会被当成「查不到名字的分类」
        return scopes.stream().collect(Collectors.groupingBy(
                CouponScope::getCouponId,
                Collectors.mapping(CouponScope::getBizId, Collectors.toList())
        ));
    }
}
