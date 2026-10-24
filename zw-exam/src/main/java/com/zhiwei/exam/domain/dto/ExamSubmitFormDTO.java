package com.zhiwei.exam.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

/**
 * 提交考试表单实体
 * <p>「开始考试」不落库，考试记录在<b>提交试卷时</b>才创建，所以这里必须带上定位信息
 * （sectionId / courseId / type），而不是考试记录 id。</p>
 * <p>字段与前端 {@code pages/learning/components/Practise.vue#postSubject} 对齐：
 * 前端提交 {@code { sectionId, courseId, lessonId, type, duration, examDetails: [{questionId, answer, questionType}] }}。</p>
 */
@Data
@Schema(description = "提交考试表单实体")
public class ExamSubmitFormDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "课表id")
    private Long lessonId;

    @Schema(description = "课程id")
    @NotNull(message = "课程id不能为空")
    private Long courseId;

    @Schema(description = "小节id（考试对应的目录id）")
    @NotNull(message = "小节id不能为空")
    private Long sectionId;

    @Schema(description = "考试类型，1-练习，2-考试")
    private Integer type;

    @Schema(description = "考试用时，单位：秒（前端从进入答题页开始计时）")
    private Integer duration;

    @Schema(description = "用户答案列表")
    @Valid
    private List<AnswerFormDTO> examDetails;

    @Data
    @Schema(description = "题目答案表单")
    public static class AnswerFormDTO implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "题目id")
        private Long questionId;

        @Schema(description = "用户答案。单选/多选为选项序号（从1开始，多选逗号分隔）；判断题 true/false；主观题为文本")
        private String answer;

        @Schema(description = "题目类型，1：单选题，2：多选题，3：不定项选择题，4：判断题，5：主观题")
        private Integer questionType;
    }
}
