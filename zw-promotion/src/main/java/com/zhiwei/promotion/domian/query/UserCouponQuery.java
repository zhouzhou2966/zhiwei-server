package com.zhiwei.promotion.domian.query;

import com.zhiwei.common.domain.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import javax.validation.constraints.NotNull;

/**
 * 用户优惠券（我的优惠券）分页查询参数
 * <p>
 * 状态是必填项：前端「我的优惠券」用三个 Tab（未使用 / 已使用 / 已过期）切换，
 * 每次切换只查一个状态，因此这里不做多状态 IN 查询，直接用单值等值过滤，
 * 能命中 user_coupon(user_id, status) 的索引。
 * </p>
 */
@EqualsAndHashCode(callSuper = true)
@Data
@ApiModel(description = "用户优惠券查询参数")
@Accessors(chain = true)
public class UserCouponQuery extends PageQuery {

    @NotNull(message = "优惠券状态不能为空")
    @ApiModelProperty("优惠券状态，1：未使用，2：已使用，3：已过期")
    private Integer status;
}
