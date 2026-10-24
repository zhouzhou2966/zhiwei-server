package com.zhiwei.promotion.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.domian.po.ExchangeCode;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.promotion.domian.query.CodeQuery;
import com.zhiwei.promotion.domian.vo.CouponScopePageVO;


/**
 * <p>
 * 兑换码 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-26
 */
public interface IExchangeCodeService extends IService<ExchangeCode> {
    // 异步生成优惠券码
    void asyncGenerateCode(Coupon coupon);

    // 查询优惠券码分页
    PageDTO<CouponScopePageVO> queryCodesPage(CodeQuery query);

    // 更新兑换码使用状态
    boolean updateExchangeMark(long serialNum, boolean mark);
}
