package com.zhiwei.pay.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.pay.domain.po.PayOrder;
import com.zhiwei.pay.sdk.dto.PayApplyDTO;
import com.zhiwei.pay.sdk.dto.PayResultDTO;

import java.time.LocalDateTime;

/**
 * <p>
 * 支付订单 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-26
 */
public interface IPayOrderService extends IService<PayOrder> {

    String applyPayOrder(PayApplyDTO payApplyDTO);

    PayOrder queryByBizOrderNo(Long bizOrderNo);

    PayResultDTO queryPayResult(Long bizOrderId);

    PayOrder queryByPayOrderNo(Long payOrderNo);

    boolean markPayOrderSuccess(Long id, LocalDateTime successTime);

    PageDTO<PayOrder> queryPayingOrderByPage(int shardIndex, int shardTotal, int size);

    void checkPayOrder(PayOrder payOrder);

    /**
     * 按业务订单号关闭支付单（供业务侧关单时调用，用于同步关闭第三方渠道的支付）。
     * <p>
     * 返回值语义与 {@link #queryPayResult(Long)} 保持一致，业务侧不需要做任何转换：
     * <ul>
     *   <li>{@code PayResultDTO.SUCCESS} —— 支付单已支付成功，<b>不能</b>关业务单，
     *       应转成「已支付」处理（结果里带了 payOrderNo / payChannel / successTime）</li>
     *   <li>{@code PayResultDTO.FAILED} —— 支付单已关闭，或压根没有支付单（用户从未发起支付），
     *       业务侧可以安全关单</li>
     *   <li>{@code PayResultDTO.PAYING} —— 渠道关单失败且无法确认用户是否已付（网络异常等），
     *       业务侧本轮应跳过，下一轮重试</li>
     * </ul>
     *
     * @param bizOrderNo 业务订单号
     * @return 关单结果
     */
    PayResultDTO closeByBizOrderNo(Long bizOrderNo);
}
