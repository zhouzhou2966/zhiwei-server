package com.zhiwei.trade.domain.vo;


import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "订单分页信息")
public class OrderPageVO {
    @Schema(description = "订单id")
    private Long id;
    @Schema(description = "订单创建时间")
    private LocalDateTime createTime;
    @Schema(description = "订单实付金额")
    private Integer realAmount;
    @Schema(description = "订单明细金额")
    private Integer totalAmount;
    @Schema(description = "订单状态1：待支付，2：已支付，3：已关闭，4：已完成，5：已报名")
    private Integer status;
    @Schema(description = "订单状态描述")
    private String statusDesc;
    /**
     * 当前是否还能去支付：待支付 <b>且</b> 还没到支付时限。
     * <p>
     * 前端「去支付」按钮必须同时看 status 和这个字段，不能只看 status。
     * 关单是异步任务，超时之后到任务真正关单之间，订单还是 status=1，
     * 但支付接口已经会拒绝 —— 只按 status 判断就会出现「显示能付、点进去说超时」。
     * </p>
     */
    @Schema(description = "当前是否还能支付（待支付且未超时）")
    private Boolean canPay;
    @Schema(description = "订单中课程明细")
    private List<OrderDetailVO> details;
}
