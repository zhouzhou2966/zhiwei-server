package com.zhiwei.learning.controller;


import com.zhiwei.learning.domian.query.PointsBoardQuery;
import com.zhiwei.learning.domian.vo.PointsBoardVO;
import com.zhiwei.learning.service.impl.PointsBoardServiceImpl;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 学霸天梯榜 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
@Tag(name = "积分排行榜接口")
@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
public class PointsBoardController {
    private final PointsBoardServiceImpl pointsBoardService;


    @Operation(summary = "分页查询指定赛季积分排行榜")
    @GetMapping
    public PointsBoardVO queryPointsBoardBySeason(PointsBoardQuery query) {
        return pointsBoardService.queryPointsBoardBySeason(query);
    }


}
