package com.zhiwei.promotion.domian.vo;

import com.zhiwei.promotion.enums.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 兑换码预检结果。
 *
 * <p>为什么需要它：兑换码兑换是个「盲盒」—— 用户手里只有一串码，不知道会换到什么券，
 * 也不知道自己是否已经领过。原来只能点了「兑换」再被一句「超出领取数量」挡住，
 * 用户既不知道在跟什么限额较劲，也会觉得「我拿的是有效码，凭什么不给我」。</p>
 *
 * <p>所以把「能不能兑、兑了得到什么」提前摆出来：前端在输入框里查一次，
 * 直接把券面信息和不可兑的原因展示出来，把盲盒变成可预期的盲盒。</p>
 */
@Data
@Schema(description = "兑换码预检结果")
public class ExchangePreviewVO {

    @Schema(description = "兑换码格式是否合法")
    private Boolean valid;

    @Schema(description = "该码是否已被使用")
    private Boolean used;

    @Schema(description = "兑换码对应的优惠券id，码无效时为空")
    private Long couponId;

    @Schema(description = "优惠券名称")
    private String name;

    @Schema(description = "优惠券类型，1：每满减，2：折扣，3：无门槛，4：普通满减")
    private DiscountType discountType;

    @Schema(description = "折扣门槛（分），0 代表无门槛")
    private Integer thresholdAmount;

    @Schema(description = "折扣值：满减填抵扣金额（分），打折填折扣值（80 表示 8 折）")
    private Integer discountValue;

    @Schema(description = "最大优惠金额（分），0 表示不封顶")
    private Integer maxDiscountAmount;

    @Schema(description = "有效天数")
    private Integer termDays;

    @Schema(description = "每人限领数量")
    private Integer userLimit;

    @Schema(description = "当前用户已持有数量")
    private Integer userCount;

    @Schema(description = "当前是否可以兑换")
    private Boolean canExchange;

    @Schema(description = "不可兑换的原因，可直接展示给用户")
    private String reason;
}
