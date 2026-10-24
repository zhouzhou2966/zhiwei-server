package com.zhiwei.exam.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 开始考试表单实体
 * <p>字段与前端 {@code pages/learning/index.vue#startExaminationHandle} 对齐：
 * 前端传 sectionId / type / courseId / lessonId。</p>
 */
@Data
@Schema(description = "开始考试表单实体")
public class ExamStartFormDTO implements Serializable {

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
}