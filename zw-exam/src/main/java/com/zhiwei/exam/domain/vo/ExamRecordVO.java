package com.zhiwei.exam.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开始考试返回数据（考试记录及题目列表，不含答案）
 */
@Data
@Accessors(chain = true)
@ApiModel(description = "考试记录及题目列表")
public class ExamRecordVO {

    @ApiModelProperty("考试记录id")
    private Long id;

    @ApiModelProperty("课表id")
    private Long lessonId;

    @ApiModelProperty("课程id")
    private Long courseId;

    @ApiModelProperty("小节id")
    private Long sectionId;

    @ApiModelProperty("考试类型，1-练习，2-考试")
    private Integer type;

    @ApiModelProperty("用户id")
    private Long userId;

    @ApiModelProperty("题目总数")
    private Integer questionCount;

    @ApiModelProperty("总分")
    private Integer totalScore;

    @ApiModelProperty("考试状态，0-未完成，1-已完成")
    private Integer status;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;

    @ApiModelProperty("完成时间")
    private LocalDateTime finishTime;

    @ApiModelProperty("题目列表（不含答案）")
    private List<QuestionDetailVO> questions;
}