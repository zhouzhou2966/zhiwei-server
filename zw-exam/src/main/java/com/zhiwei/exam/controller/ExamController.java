package com.zhiwei.exam.controller;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.domain.query.PageQuery;
import com.zhiwei.exam.domain.dto.ExamStartFormDTO;
import com.zhiwei.exam.domain.dto.ExamSubmitFormDTO;
import com.zhiwei.exam.domain.vo.ExamDetailVO;
import com.zhiwei.exam.domain.vo.ExamRecordPageVO;
import com.zhiwei.exam.domain.vo.ExamRecordVO;
import com.zhiwei.exam.domain.vo.ExamStatsVO;
import com.zhiwei.exam.service.IExamRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 考试/练习接口
 * <p>网关路由 {@code /es/**} -> exam-service，StripPrefix=1，所以这里的
 * {@code /exams} 对应前端调用的 {@code /es/exams}。</p>
 */
@Tag(name = "考试记录接口")
@RestController
@RequestMapping("/exams")
@RequiredArgsConstructor
public class ExamController {

    private final IExamRecordService examRecordService;

    @Operation(summary = "开始考试，按小节取题")
    @PostMapping
    public ExamRecordVO startExam(@Valid @RequestBody ExamStartFormDTO startFormDTO) {
        return examRecordService.startExam(startFormDTO);
    }

    @Operation(summary = "提交考试答案并批阅")
    @PostMapping("/details")
    public List<ExamDetailVO> submitExam(@Valid @RequestBody ExamSubmitFormDTO submitFormDTO) {
        return examRecordService.submitExam(submitFormDTO);
    }

    @Operation(summary = "分页查询我的考试记录")
    @GetMapping("/page")
    public PageDTO<ExamRecordPageVO> queryExamRecordPage(PageQuery pageQuery) {
        return examRecordService.queryExamRecordPage(pageQuery);
    }

    @Operation(summary = "我的考试 · 全量统计（次数 / 均分 / 最高分）")
    @GetMapping("/stats")
    public ExamStatsVO queryExamStats() {
        return examRecordService.queryExamStats();
    }

    @Operation(summary = "查询当前用户已完成考试的小节id列表（课程目录打勾用）")
    @GetMapping("/finished-sections")
    public List<Long> queryFinishedSectionIds() {
        return examRecordService.queryFinishedSectionIds();
    }

    @Operation(summary = "查询考试记录详情（逐题批阅结果）")
    @GetMapping("/{id}")
    public List<ExamDetailVO> queryExamDetail(@PathVariable("id") Long id) {
        return examRecordService.queryExamDetail(id);
    }
}
