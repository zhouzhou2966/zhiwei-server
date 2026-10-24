package com.zhiwei.pay.sdk.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ApiModel(description = "退款请求参数")
public class RefundApplyDTO {

    @ApiModelProperty("支付时传入的业务订单id")
    @NotNull(message = "业务订单id不能为空")
    private Long bizOrderNo;
    @ApiModelProperty("本次要退款的业务订单id，因为有拆单，这里是子订单id")
    @NotNull(message = "业务退款订单id不能为空")
    private Long bizRefundOrderNo;
    @ApiModelProperty("子订单的退款金额,单位为分")
    @NotNull(message = "退款金额不能为空")
    @Min(value = 1, message = "退款金额必须为正数")
    private Integer refundAmount;
}