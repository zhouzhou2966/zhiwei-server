package com.zhiwei.pay.controller;


import com.zhiwei.pay.sdk.dto.RefundApplyDTO;
import com.zhiwei.pay.sdk.dto.RefundResultDTO;
import com.zhiwei.pay.service.IRefundOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * <p>
 * 退款订单 前端控制器
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-26
 */
@RestController
@RequestMapping("/refund-orders")
@RequiredArgsConstructor
@Tag(name = "支付相关接口")
public class RefundOrderController {

    private final IRefundOrderService refundOrderService;

    @PostMapping
    @Operation(summary = "申请退款接口")
    public RefundResultDTO applyRefund(@Valid @RequestBody RefundApplyDTO refundApplyDTO) {
        return refundOrderService.applyRefund(refundApplyDTO);
    }

    @GetMapping("{bizRefundOrderId}/status")
    @Operation(summary = "查询退款结果")
    public RefundResultDTO queryRefundResult(
            @Parameter(description = "业务端退款的子订单id") @PathVariable("bizRefundOrderId") Long bizRefundOrderId) {
        return refundOrderService.queryRefundResult(bizRefundOrderId);
    }

/*    @GetMapping("{bizRefundOrderId}/detail")
    @Operation(summary = "查询退款详情")
    public RefundResultDTO queryRefundDetail(
            @Parameter(description = "业务端退款的子订单id") @PathVariable("bizRefundOrderId") Long bizRefundOrderId) {
        return refundOrderService.queryRefundDetail(bizRefundOrderId);
    }*/
}
