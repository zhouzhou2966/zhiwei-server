package com.zhiwei.learning.controller;


import com.zhiwei.api.dto.leanring.LearningLessonDTO;
import com.zhiwei.learning.domian.dto.LearningRecordFormDTO;
import com.zhiwei.learning.domian.vo.LearningSummaryVO;
import com.zhiwei.learning.service.ILearningLessonService;
import com.zhiwei.learning.service.ILearningRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
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
@Tag(name = "学习记录的相关接口")
public class LearningRecordController {
    private final ILearningRecordService learningRecordService;

    @Operation(summary = "查询指定课程的学习记录")
    @GetMapping("/course/{courseId}")
    LearningLessonDTO queryLearningRecordByCourse(
            @Parameter(description = "课程id", example = "2") @PathVariable("courseId") Long courseId) {
        return learningRecordService.queryLearningRecordByCourse(courseId);
    }


    @PostMapping
    @Operation(summary = "提交学习记录")
    void addLearningRecord(@RequestBody LearningRecordFormDTO learningRecordFormDTO) {
        learningRecordService.addLearningRecord(learningRecordFormDTO);
    }

    @GetMapping("/summary")
    @Operation(summary = "学习活跃度按天聚合（概览页本周节奏、报告页热力图）")
    public LearningSummaryVO learningSummary(
            @Parameter(description = "统计天数，1-90，默认 30", example = "7")
            @RequestParam(value = "days", required = false) Integer days) {
        return learningRecordService.learningSummary(days);
    }

}
