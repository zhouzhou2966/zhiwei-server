package com.zhiwei.exam.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试记录分页数据
 */
@Data
@Schema(description = "考试记录分页数据")
public class ExamRecordPageVO {

    @Schema(description = "考试记录id")
    private Long id;

    @Schema(description = "课表id")
    private Long lessonId;

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "课程名称（跨服务查课程服务填充）")
    private String courseName;

    @Schema(description = "小节（章节）名称（跨服务查课程服务填充）")
    private String sectionName;

    @Schema(description = "小节id")
    private Long sectionId;

    @Schema(description = "考试类型，1-练习，2-考试")
    private Integer type;

    @Schema(description = "题目总数")
    private Integer questionCount;

    @Schema(description = "总分")
    private Integer totalScore;

    @Schema(description = "得分")
    private Integer score;

    @Schema(description = "正确率，例如8550表示85.5%")
    private Integer correctRate;

    @Schema(description = "考试用时，单位：秒")
    private Integer duration;

    @Schema(description = "考试状态，0-未完成，1-已完成")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    @Schema(description = "提交时间（未提交为 null）。前端列表用 commitTime 显示「考试时间」")
    private LocalDateTime commitTime;
}