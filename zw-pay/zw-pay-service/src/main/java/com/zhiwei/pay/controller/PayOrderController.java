package com.zhiwei.pay.controller;


import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.pay.sdk.constants.PayErrorInfo;
import com.zhiwei.pay.sdk.constants.PayType;
import com.zhiwei.pay.sdk.dto.PayApplyDTO;
import com.zhiwei.pay.sdk.dto.PayResultDTO;
import com.zhiwei.pay.service.IPayOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * <p>
 * 支付订单 前端控制器
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-26
 */
@Tag(name = "支付相关接口")
@RestController
@RequestMapping("/pay-orders")
@RequiredArgsConstructor
public class PayOrderController {

    private final IPayOrderService payOrderService;

    @Operation(summary = "扫码支付申请支付单，返回支付url地址，用于生产二维码")
    @PostMapping
    public String applyPayOrder(@Valid @RequestBody PayApplyDTO payApplyDTO){
        if(!PayType.NATIVE.equalsValue(payApplyDTO.getPayType())){
            throw new BadRequestException(PayErrorInfo.INVALID_PAY_TYPE);
        }
        return payOrderService.applyPayOrder(payApplyDTO);
    }

    @Operation(summary = "根据业务端订单id查询支付结果")
    @GetMapping("/{bizOrderId}/status")
    public PayResultDTO queryPayResult(
            @Parameter(description = "业务订单id") @PathVariable("bizOrderId") Long bizOrderId
    ){
        return payOrderService.queryPayResult(bizOrderId);
    }

    /**
     * 关闭支付单（内部接口，仅供业务侧关单时调用）。
     * <p>
     * 为什么必须由 pay 侧来关：用户手里的二维码是第三方（支付宝/微信）生成的，
     * 只把本地 pay_order 置为已关闭没有用 —— 二维码在渠道侧依然有效、依然能付款。
     * 业务侧关单后如果不同步关掉渠道订单，会出现「钱收了、订单已关闭、用户没课」的资损。
     * </p>
     * <p>
     * 鉴权说明：pay 的 {@code /ps/**} 没有用户身份校验，本接口是<b>写操作</b>，
     * 裸奔意味着任何人只要拿到 bizOrderNo 就能关掉别人的支付单。
     * 现在由 {@code InnerTokenFilter} 在过滤器层统一校验 {@code X-Inner-Token}，
     * 覆盖本接口在内的所有非回调接口，所以这里不再单独校验密钥。
     * </p>
     */
    @Operation(summary = "关闭支付单（内部接口）")
    @PostMapping("/{bizOrderNo}/close")
    public PayResultDTO closePayOrder(
            @Parameter(description = "业务订单id") @PathVariable("bizOrderNo") Long bizOrderNo
    ){
        return payOrderService.closeByBizOrderNo(bizOrderNo);
    }
}
