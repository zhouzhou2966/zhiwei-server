package com.zhiwei.pay.tasks;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.pay.domain.po.PayOrder;
import com.zhiwei.pay.service.IPayOrderService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PayOrderCheckTask {

    private final IPayOrderService payOrderService;

    @XxlJob("payOrderCheckHandler")
    public void checkPayOrderStatus() {
        // 1.获取分片信息（SHARDING_BROADCAST 下每台实例拿到自己的分片号）
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        String jobParam = XxlJobHelper.getJobParam();
        int size = StringUtils.isNumeric(jobParam) ? Integer.parseInt(jobParam) : 100;
        // 2.查询需要处理的支付订单
        PageDTO<PayOrder> result = payOrderService.queryPayingOrderByPage(shardIndex, shardTotal, size);
        if (result.isEmpty()) {
            return;
        }
        // 3.逐个检查支付单
        for (PayOrder payOrder : result.getList()) {
            try {
                payOrderService.checkPayOrder(payOrder);
            } catch (Exception e) {
                log.error("处理订单支付状态异常：", e);
            }
        }
    }
}
