package com.zhiwei.pay.tasks;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.pay.domain.po.RefundOrder;
import com.zhiwei.pay.service.IRefundOrderService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefundOrderCheckTask {

    private final IRefundOrderService refundOrderService;

    @XxlJob("refundOrderCheckHandler")
    public void checkRefundOrderStatus() {
        // 1.获取分片信息（SHARDING_BROADCAST 下每台实例拿到自己的分片号）
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        String jobParam = XxlJobHelper.getJobParam();
        int size = StringUtils.isNumeric(jobParam) ? Integer.parseInt(jobParam) : 100;
        // 2.查询需要处理的退款订单
        PageDTO<RefundOrder> result = refundOrderService.queryRefundingOrderByPage(shardIndex, shardTotal, size);
        if (result.isEmpty()) {
            return;
        }
        // 3.逐个检查退款单
        for (RefundOrder refundOrder : result.getList()) {
            try {
                refundOrderService.checkRefundOrder(refundOrder);
            } catch (Exception e) {
                log.error("处理退款订单状态异常：", e);
            }
        }
    }
}
