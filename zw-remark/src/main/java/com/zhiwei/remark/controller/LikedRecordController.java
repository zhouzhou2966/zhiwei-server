package com.zhiwei.remark.controller;


import com.zhiwei.remark.domian.dto.LikeRecordFormDTO;
import com.zhiwei.remark.service.impl.LikedRecordServiceRedisImpl;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 点赞记录表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-13
 */
@RestController
@RequestMapping("/likes")
@Api(tags = "点赞业务相关接口")
@RequiredArgsConstructor
public class LikedRecordController {
    private final LikedRecordServiceRedisImpl likedRecordService;

    @ApiOperation("点赞或者取消点赞")
    @PostMapping
    public void likeOrUnlike(@Valid @RequestBody LikeRecordFormDTO likeRecordFormDTO) {
        likedRecordService.likeOrUnlike(likeRecordFormDTO);
    }

    @ApiOperation("查询指定业务id的点赞状态")
    @GetMapping("/list")
    public Set<Long> isBizLike(@RequestParam("bizIds") List<Long> bizIds,
                               @RequestParam("bizType") String bizType) {
        return likedRecordService.isBizLike(bizIds, bizType);
    }

}
