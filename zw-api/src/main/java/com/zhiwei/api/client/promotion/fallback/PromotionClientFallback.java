package com.zhiwei.api.client.promotion.fallback;

import com.zhiwei.api.client.promotion.PromotionClient;
import com.zhiwei.api.dto.promotion.CouponDiscountDTO;
import com.zhiwei.api.dto.promotion.OrderCouponDTO;
import com.zhiwei.api.dto.promotion.OrderCourseDTO;
import com.zhiwei.common.exceptions.BizIllegalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collections;
import java.util.List;

@Slf4j
public class PromotionClientFallback implements FallbackFactory<PromotionClient> {
    @Override
    public PromotionClient create(Throwable cause) {
        log.error("查询促销服务出现异常,", cause);

        return new PromotionClient() {
            @Override
            public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses) {
                return Collections.emptyList();
            }

            @Override
            public CouponDiscountDTO queryDiscountDetailByOrder(OrderCouponDTO orderCouponDTO) {
                return null;
            }

            @Override
            public void writeOffCoupon(List<Long> couponIds) {
                throw new BizIllegalException(500, "核销优惠券异常", cause);
            }

            @Override
            public void refundCoupon(List<Long> couponIds) {
                throw new BizIllegalException(500, "退款优惠券异常", cause);
            }

            @Override
            public List<String> queryCouponRules(List<Long> couponIds) {
                log.error("查询优惠券规则出现异常,", cause);
                return Collections.emptyList();
            }
        };

    }


}
