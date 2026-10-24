package com.zhiwei.learning.controller;


import com.zhiwei.learning.domian.vo.PointsStatisticsVO;
import com.zhiwei.learning.service.IPointsRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
@RestController
@RequestMapping("/points")
@RequiredArgsConstructor
@Tag(name = "学习积分记录接口")
public class PointsRecordController {
    private final IPointsRecordService pointsRecordService;

    @Operation(summary = "查询今日积分")
    @GetMapping("/today")
    public List<PointsStatisticsVO> queryTodayPoints() {
        return pointsRecordService.queryTodayPoints();

    }

}
