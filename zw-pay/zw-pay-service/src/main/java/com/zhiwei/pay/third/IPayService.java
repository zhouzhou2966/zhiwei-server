package com.zhiwei.pay.third;

import com.zhiwei.pay.third.model.PayStatusResponse;
import com.zhiwei.pay.third.model.PrepayResponse;
import com.zhiwei.pay.third.model.RefundResponse;

/**
 * 统一支付服务接口
 */
public interface IPayService {

    PrepayResponse createPrepayOrder(String title, String orderNo, Integer amount);

    PayStatusResponse queryPayOrderStatus(String payOrderNo);

    /**
     * 关闭第三方渠道侧的支付订单。
     * <p>
     * 本地把支付单置为「已关闭」之前必须先调用它，否则会出现「本地已关闭、渠道侧仍可支付」
     * 的脱节：用户手里的二维码还能付款，而我们再也不会去查这笔单，最终就是钱收了、单关了。
     * </p>
     * <p>
     * 实现约定：只有「确定渠道侧已经不可支付」才返回 true。已支付、网络异常、渠道拒绝
     * 一律返回 false，由调用方重新查单确认真实状态 —— 不要靠错误码去猜，
     * 把「已支付」误判成「已关闭」的代价远大于多重试一轮。
     * </p>
     *
     * @param payOrderNo 支付单号
     * @return true 表示渠道侧确认已关闭；false 表示没能关掉，需要调用方查单确认
     */
    boolean closeOrder(String payOrderNo);

    RefundResponse refundOrder(String payOrderNo, String refundOrderNo, Integer refundAmount, Integer totalAmount);

    RefundResponse queryRefundStatus(String orderNo, String refundOrderNo);
}
