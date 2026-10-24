package com.zhiwei.exam.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 考试记录详情（用户答题的每一道题信息）
 */
@Data
@Schema(description = "考试记录详情")
public class ExamDetailVO {

    @Schema(description = "学员答案")
    private String answer;

    @Schema(description = "老师评语")
    private String comment;

    @Schema(description = "是否正确")
    private Boolean correct;

    @Schema(description = "学员得分")
    private Integer score;

    @Schema(description = "题目信息")
    private QuestionVO question;

    @Data
    @Schema(description = "题目信息")
    public static class QuestionVO {

        @Schema(description = "问题id")
        private Long id;

        @Schema(description = "问题名称")
        private String name;

        @Schema(description = "问题类型，1：单选题，2：多选题，3：不定向选择题，4：判断题，5：主观题")
        private Integer type;

        @Schema(description = "问题分值")
        private Integer score;

        @Schema(description = "选项")
        private List<String> options;

        @Schema(description = "正确答案")
        private String answer;

        @Schema(description = "答案解析")
        private String analysis;

        @Schema(description = "难易程度，1：简单，2：中等，3：困难")
        private Integer difficulty;
    }
}