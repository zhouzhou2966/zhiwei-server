package com.zhiwei.learning.controller;


import com.zhiwei.learning.domian.vo.PointsStatisticsVO;
import com.zhiwei.learning.domian.vo.SignResultVO;
import com.zhiwei.learning.service.ISignRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 学霸天梯榜 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
@Tag(name = "签到记录的相关接口")
@RestController
@RequestMapping("/sign-records")
@RequiredArgsConstructor
public class SignRecordController {
    private final ISignRecordService signRecordService;


    @Operation(summary = "签到功能")
    @PostMapping
    public SignResultVO addSignRecord() {
        return signRecordService.addSignRecord();
    }


    @GetMapping
    @Operation(summary = "查询签到记录")
    public List<Integer> querySignRecord() {
        return signRecordService.querySignRecord();
    }



}
