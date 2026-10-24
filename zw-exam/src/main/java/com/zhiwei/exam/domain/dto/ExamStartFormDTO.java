package com.zhiwei.exam.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 开始考试表单实体
 * <p>字段与前端 {@code pages/learning/index.vue#startExaminationHandle} 对齐：
 * 前端传 sectionId / type / courseId / lessonId。</p>
 */
@Data
@ApiModel(description = "开始考试表单实体")
public class ExamStartFormDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("课表id")
    private Long lessonId;

    @ApiModelProperty("课程id")
    @NotNull(message = "课程id不能为空")
    private Long courseId;

    @ApiModelProperty("小节id（考试对应的目录id）")
    @NotNull(message = "小节id不能为空")
    private Long sectionId;

    @ApiModelProperty("考试类型，1-练习，2-考试")
    private Integer type;
}