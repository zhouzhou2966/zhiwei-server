package com.zhiwei.exam.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试记录分页数据
 */
@Data
@ApiModel(description = "考试记录分页数据")
public class ExamRecordPageVO {

    @ApiModelProperty("考试记录id")
    private Long id;

    @ApiModelProperty("课表id")
    private Long lessonId;

    @ApiModelProperty("课程id")
    private Long courseId;

    @ApiModelProperty("课程名称（跨服务查课程服务填充）")
    private String courseName;

    @ApiModelProperty("小节（章节）名称（跨服务查课程服务填充）")
    private String sectionName;

    @ApiModelProperty("小节id")
    private Long sectionId;

    @ApiModelProperty("考试类型，1-练习，2-考试")
    private Integer type;

    @ApiModelProperty("题目总数")
    private Integer questionCount;

    @ApiModelProperty("总分")
    private Integer totalScore;

    @ApiModelProperty("得分")
    private Integer score;

    @ApiModelProperty("正确率，例如8550表示85.5%")
    private Integer correctRate;

    @ApiModelProperty("考试用时，单位：秒")
    private Integer duration;

    @ApiModelProperty("考试状态，0-未完成，1-已完成")
    private Integer status;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;

    @ApiModelProperty("完成时间")
    private LocalDateTime finishTime;

    @ApiModelProperty("提交时间（未提交为 null）。前端列表用 commitTime 显示「考试时间」")
    private LocalDateTime commitTime;
}