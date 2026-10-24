package com.zhiwei.promotion.domian.vo;

import com.zhiwei.promotion.domian.po.CouponScope;
import com.zhiwei.promotion.domian.po.ExchangeCode;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@ApiModel(description = "优惠券兑换码")
@NoArgsConstructor
@AllArgsConstructor
public class CouponScopePageVO {
    @ApiModelProperty("兑换码id")
    private Integer id;
    private String code;
}
