package com.zhiwei.promotion.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.promotion.domian.query.CodeQuery;
import com.zhiwei.promotion.domian.vo.CouponScopePageVO;
import com.zhiwei.promotion.service.IExchangeCodeService;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 兑换码 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-26
 */
@RestController
@RequestMapping("/codes")
@RequiredArgsConstructor
public class ExchangeCodeController {
    private final IExchangeCodeService exchangeCodeService;

    @ApiOperation("分页查询兑换码")
    @GetMapping("/page")
    public PageDTO<CouponScopePageVO> queryCodesPage(CodeQuery query) {
        return exchangeCodeService.queryCodesPage(query);
    }

}
