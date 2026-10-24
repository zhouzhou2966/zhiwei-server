package com.zhiwei.pay.third;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "zw.pay")
public class CommonPayProperties {
    private String notifyHost;

    /**
     * 支付单的有效期，单位分钟。
     * <p>
     * 原来 buildPayOrder 里硬编码 120 分钟，而业务侧（zw-trade 的 zw.trade.payOrderTTLMinutes）是 30 分钟，
     * 两者差 90 分钟。业务侧关单后，用户手里的二维码在 pay 侧仍然有效、仍然能付款，
     * 回调过来 trade 的 handlePaySuccess 条件更新（eq status = 待支付）失败后静默跳过 ——
     * 钱收了、订单已关闭、用户没课。所以这里必须和业务侧对齐。
     * </p>
     */
    private int orderTTLMinutes = 30;

    /**
     * 内部服务调用的共享密钥。
     * <p>
     * 关单接口（POST /pay-orders/{bizOrderNo}/close）是写操作，而 pay 的 /ps/** 整条链路
     * 没有接 auth sdk、网关也不做鉴权，等于公网任何人只要知道 bizOrderNo 就能关掉别人的支付单。
     * 用这个共享密钥把写接口限制成「只有内部服务能调」。
     * </p>
     * <p>
     * 未配置时关单接口一律拒绝（403），不允许「忘记配 = 裸奔」。
     * 配置位置：Nacos 的 pay-service.yaml 与 trade-service.yaml，两边必须一致。
     * </p>
     */
    private String innerToken;
}
