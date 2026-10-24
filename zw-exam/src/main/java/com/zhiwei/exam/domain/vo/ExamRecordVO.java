package com.zhiwei.exam.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开始考试返回数据（考试记录及题目列表，不含答案）
 */
@Data
@Accessors(chain = true)
@Schema(description = "考试记录及题目列表")
public class ExamRecordVO {

    @Schema(description = "考试记录id")
    private Long id;

    @Schema(description = "课表id")
    private Long lessonId;

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "小节id")
    private Long sectionId;

    @Schema(description = "考试类型，1-练习，2-考试")
    private Integer type;

    @Schema(description = "用户id")
    private Long userId;

    @Schema(description = "题目总数")
    private Integer questionCount;

    @Schema(description = "总分")
    private Integer totalScore;

    @Schema(description = "考试状态，0-未完成，1-已完成")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    @Schema(description = "题目列表（不含答案）")
    private List<QuestionDetailVO> questions;
}