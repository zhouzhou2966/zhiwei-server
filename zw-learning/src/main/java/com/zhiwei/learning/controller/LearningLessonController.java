package com.zhiwei.learning.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.domain.query.PageQuery;
import com.zhiwei.learning.domian.dto.LearningPlanDTO;
import com.zhiwei.learning.domian.vo.LearningLessonVO;
import com.zhiwei.learning.domian.vo.LearningPlanPageVO;
import com.zhiwei.learning.service.ILearningLessonService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * <p>
 * 学生课程表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@RestController
@RequestMapping("/lessons")
@RequiredArgsConstructor
@Tag(name = "我的课程表相关接口")
public class LearningLessonController {
    private final ILearningLessonService lessonService;

    @GetMapping("/page")
    @Operation(summary = "分页查询我的课程表")
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery pageQuery) {
        return lessonService.queryMyLessons(pageQuery);
    }

    @GetMapping("/now")
    @Operation(summary = "获取当前正在学习的课程")
    public LearningLessonVO getNowLearningLesson() {
        return lessonService.getNowLesson();
    }

    @DeleteMapping("/{courseId}")
    @Operation(summary = "删除课表中课程")
    public void deleteMyLesson(@PathVariable Long courseId) {
        lessonService.deleteMyLesson(courseId);
    }


    @GetMapping("/{courseId}/valid")
    @Operation(summary = "判断课程是否有效")
    public Long isLessonValid(@PathVariable("courseId") Long courseId) {
        return lessonService.isLessonValid(courseId);
    }

    @GetMapping("/{courseId}")
    @Operation(summary = "获取课程信息")
    public LearningLessonVO getLessonInfo(@PathVariable("courseId") Long courseId) {
        return lessonService.getLessonInfo(courseId);
    }

    @GetMapping("/{courseId}/count")
    @Operation(summary = "统计课程的学习人数")
    public Long countLearningLessonByCourse(@PathVariable("courseId") Long courseId) {
        return lessonService.countLearningLessonByCourse(courseId);
    }

    @Operation(summary = "创建学习计划")
    @PostMapping("/plans")
    public void createLearningPlans(@Valid @RequestBody LearningPlanDTO learningPlanDTO) {
        lessonService.createLearningPlans(learningPlanDTO);
    }

    @Operation(summary = "查询我的学习计划")
    @GetMapping("/plans")
    public LearningPlanPageVO queryMyPlans(PageQuery pageQuery) {
        return lessonService.queryMyPlans(pageQuery);
    }

}
