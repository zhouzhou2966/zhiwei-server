package com.zhiwei.pay.sdk.client;

import com.zhiwei.pay.sdk.config.PayInnerTokenFeignConfig;
import com.zhiwei.pay.sdk.dto.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * pay 服务的内部调用客户端。
 * <p>
 * pay 的 {@code /ps/**} 整条链路没有用户身份校验（没引 auth sdk、网关也不鉴权），
 * 其中的申请退款、增改支付渠道都是写操作，公网裸奔等于资损。所以 pay 侧对所有非回调接口
 * 统一校验 {@code X-Inner-Token}，这里通过 {@link PayInnerTokenFeignConfig} 自动注入该请求头，
 * 业务代码不用再逐个方法传密钥。
 * </p>
 */
@FeignClient(value = "pay-service", configuration = PayInnerTokenFeignConfig.class)
public interface PayClient {
    /**
     * 查询支付渠道
     * @return 支付渠道列表
     */
    @GetMapping("/pay-channels/list")
    List<PayChannelDTO> listAllPayChannels();
    /**
     * 扫码支付申请支付单，返回支付url地址，用于生产二维码
     *
     * @param payApplyDTO 申请支付单的参数信息
     * @return 支付链接，需要前端生成二维码
     */
    @PostMapping("/pay-orders")
    String applyPayOrder(@RequestBody PayApplyDTO payApplyDTO);

    /**
     * 根据业务端订单id查询支付结果
     *
     * @param bizOrderId 业务订单id
     * @return 支付结果
     */
    @GetMapping("/pay-orders/{bizOrderId}/status")
    PayResultDTO queryPayResult(@PathVariable("bizOrderId") Long bizOrderId);

    /**
     * 按业务订单号关闭支付单，用于业务侧关单时同步关闭第三方渠道的二维码。
     * <p>
     * 返回语义与 {@link #queryPayResult(Long)} 一致：
     * SUCCESS = 已支付（不能关业务单，应直接走支付成功逻辑）、
     * FAILED = 已关闭或无支付单（可安全关业务单）、
     * PAYING = 暂时关不掉（本轮跳过，下轮重试）。
     * </p>
     *
     * @param bizOrderNo 业务订单号
     * @return 关单结果
     */
    @PostMapping("/pay-orders/{bizOrderNo}/close")
    PayResultDTO closePayOrder(@PathVariable("bizOrderNo") Long bizOrderNo);

    /**
     * 申请退款接口
     *
     * @param refundApplyDTO 退款参数
     * @return 退款结果
     */
    @PostMapping("/refund-orders")
    RefundResultDTO applyRefund(@RequestBody RefundApplyDTO refundApplyDTO);

    /**
     * 查询退款结果
     *
     * @param bizRefundOrderId 要退款的订单id
     * @return 退款结果
     */
    @GetMapping("/refund-orders/{bizRefundOrderId}/status")
    RefundResultDTO queryRefundResult(@PathVariable("bizRefundOrderId") Long bizRefundOrderId);
}
