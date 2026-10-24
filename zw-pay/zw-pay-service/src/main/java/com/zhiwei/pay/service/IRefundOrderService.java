package com.zhiwei.pay.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.pay.domain.po.RefundOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.pay.sdk.dto.RefundApplyDTO;
import com.zhiwei.pay.sdk.dto.RefundResultDTO;

/**
 * <p>
 * 退款订单 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-26
 */
public interface IRefundOrderService extends IService<RefundOrder> {

    RefundResultDTO applyRefund(RefundApplyDTO refundApplyDTO);

    RefundResultDTO queryRefundResult(Long bizRefundOrderId);

    RefundOrder queryByRefundOrderNo(Long refundOrderNo);

    PageDTO<RefundOrder> queryRefundingOrderByPage(int shardIndex, int shardTotal, int size);

    void checkRefundOrder(RefundOrder refundOrder);

    RefundResultDTO queryRefundDetail(Long bizRefundOrderId);
}
