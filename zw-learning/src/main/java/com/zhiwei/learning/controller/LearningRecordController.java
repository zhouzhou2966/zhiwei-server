package com.zhiwei.learning.controller;


import com.zhiwei.api.dto.leanring.LearningLessonDTO;
import com.zhiwei.learning.domian.dto.LearningRecordFormDTO;
import com.zhiwei.learning.service.ILearningLessonService;
import com.zhiwei.learning.service.ILearningRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.units.qual.A;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 学习记录表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-06
 */
@RestController
@RequestMapping("/learning-records")
@RequiredArgsConstructor
@Api(tags = "学习记录的相关接口")
public class LearningRecordController {
    private final ILearningRecordService learningRecordService;

    @ApiOperation(value = "查询指定课程的学习记录")
    @GetMapping("/course/{courseId}")
    LearningLessonDTO queryLearningRecordByCourse(
            @ApiParam(value = "课程id", example = "2") @PathVariable("courseId") Long courseId) {
        return learningRecordService.queryLearningRecordByCourse(courseId);
    }


    @PostMapping
    @ApiOperation("提交学习记录")
    void addLearningRecord(@RequestBody LearningRecordFormDTO learningRecordFormDTO) {
        learningRecordService.addLearningRecord(learningRecordFormDTO);
    }

}
