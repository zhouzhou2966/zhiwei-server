package com.zhiwei.exam.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/**
 * 考试记录详情（用户答题的每一道题信息）
 */
@Data
@ApiModel(description = "考试记录详情")
public class ExamDetailVO {

    @ApiModelProperty("学员答案")
    private String answer;

    @ApiModelProperty("老师评语")
    private String comment;

    @ApiModelProperty("是否正确")
    private Boolean correct;

    @ApiModelProperty("学员得分")
    private Integer score;

    @ApiModelProperty("题目信息")
    private QuestionVO question;

    @Data
    @ApiModel(description = "题目信息")
    public static class QuestionVO {

        @ApiModelProperty("问题id")
        private Long id;

        @ApiModelProperty("问题名称")
        private String name;

        @ApiModelProperty("问题类型，1：单选题，2：多选题，3：不定向选择题，4：判断题，5：主观题")
        private Integer type;

        @ApiModelProperty("问题分值")
        private Integer score;

        @ApiModelProperty("选项")
        private List<String> options;

        @ApiModelProperty("正确答案")
        private String answer;

        @ApiModelProperty("答案解析")
        private String analysis;

        @ApiModelProperty("难易程度，1：简单，2：中等，3：困难")
        private Integer difficulty;
    }
}