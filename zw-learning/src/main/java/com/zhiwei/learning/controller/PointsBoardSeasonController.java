package com.zhiwei.learning.controller;


import com.zhiwei.learning.domian.vo.PointsBoardSeasonVO;
import com.zhiwei.learning.service.impl.PointsBoardSeasonServiceImpl;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
@Api(tags = "积分榜赛季")
@RestController
@RequestMapping("/boards/seasons")
@RequiredArgsConstructor
public class PointsBoardSeasonController {
    private final PointsBoardSeasonServiceImpl pointsBoardSeasonService;

    @ApiOperation("获取积分榜赛季列表")
    @GetMapping("/list")
    public List<PointsBoardSeasonVO> getSeasonsList() {
        return pointsBoardSeasonService.getSeasonsList();
    }

}
