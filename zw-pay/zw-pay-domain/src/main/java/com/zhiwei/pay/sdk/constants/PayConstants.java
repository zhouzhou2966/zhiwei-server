package com.zhiwei.pay.sdk.constants;

public interface PayConstants {
    String ALI_CHANNEL_CODE = "aliPay";
    String WX_CHANNEL_CODE = "wxPay";

    /**
     * 内部服务调用的共享密钥请求头。
     * <p>
     * 常量放在 domain 模块而不是 api 模块：pay-service（校验方）只依赖 domain、不依赖 api
     * （api 会带进 openfeign 依赖和 {@code @EnableFeignClients}，服务端用不上），
     * 放在 domain 才能让「发送方 trade 的 Feign 拦截器」和「校验方 pay 的过滤器」共用同一个定义。
     * </p>
     */
    String INNER_TOKEN_HEADER = "X-Inner-Token";

    /**
     * redis key前缀
     */
   interface RedisKeyFormatter {
        String PAY_APPLY = "pay:apply:bizOrderNo:#{payApplyDTO.bizOrderNo}";
        String PAY_NOTIFY = "pay:notify:payOrderNo:#{tradingOrderNo}";
        String PAY_ORDER_CHECK_TASK = "pay:notify:payOrderNo:#{payOrder.payOrderNo}";

        String REFUND_APPLY = "pay:refund:bizOrderNo:#{refundApplyDTO.bizOrderNo}";

        String PAY_GET_NOTIFY_LOCK = "PAY:GET_NOTIFY_ORDERS";
        String REFUND_NOTIFY = "REFUND:NOTIFY_#{refundOrder.id}";
        String REFUND_GET_NOFIGY_LOCK = "REFUND:GET_NOTIFY_ORDERS";
        String REFUND_QUERY_TASK = "PAY:QUERY_#{refundOrderId}";
    }
}
