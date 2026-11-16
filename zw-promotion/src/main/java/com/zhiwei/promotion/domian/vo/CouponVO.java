package com.zhiwei.promotion.domian.vo;

import com.zhiwei.promotion.enums.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "用户端优惠券信息")
public class CouponVO {
    @Schema(description = "优惠券id，新增不需要添加，更新必填")
    private Long id;
    @Schema(description = "优惠券名称")
    private String name;
    @Schema(description = "是否限定使用范围")
    private Boolean specific;
    /**
     * 限定范围要透给前端，否则页面只能写死一句「指定课程」，
     * 用户看不出这张券到底能用在哪些分类上 —— 而这正是他决定领不领的依据。
     * 只返回分类id，名称由前端用分类树（/cs/categorys/all）本地映射，避免跨服务调用。
     */
    @Schema(description = "限定的三级分类id列表，specific=true 时返回")
    private List<Long> scopeCategoryIds;

    @Schema(description = "优惠券类型，1：每满减，2：折扣，3：无门槛，4：普通满减")
    private DiscountType discountType;
    @Schema(description = "折扣门槛，0代表无门槛")
    private Integer thresholdAmount;
    @Schema(description = "折扣值，满减填抵扣金额；打折填折扣值：80标示打8折")
    private Integer discountValue;
    @Schema(description = "最大优惠金额")
    private Integer maxDiscountAmount;

    @Schema(description = "有效天数")
    private Integer termDays;
    @Schema(description = "使用有效期开始时间（取自用户券，termDays 券在领取时才确定）")
    private LocalDateTime termBeginTime;
    @Schema(description = "使用有效期结束时间（取自用户券，termDays 券在领取时才确定）")
    private LocalDateTime termEndTime;

    @Schema(description = "用户券id，仅「我的优惠券」列表返回")
    private Long userCouponId;
    /**
     * 注意不要叫 status：queryIssuingCoupons 用 BeanUtils.copyBean 从 Coupon 复制，
     * Coupon.status 是 CouponStatus 枚举，复制到 Integer 会变成 ordinal，语义完全错位。
     */
    @Schema(description = "用户券状态，1：未使用，2：已使用，3：已过期，仅「我的优惠券」列表返回")
    private Integer userCouponStatus;

    @Schema(description = "是否可以领取")
    private Boolean available;

    @Schema(description = "是否可以使用")
    private Boolean received;
}
