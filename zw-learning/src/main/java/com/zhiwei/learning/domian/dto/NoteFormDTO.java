package com.zhiwei.learning.domian.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@ApiModel(description = "笔记表单信息")
public class NoteFormDTO {

    @ApiModelProperty("笔记内容")
    @NotNull(message = "笔记内容不能为空")
    private String content;

    @ApiModelProperty("是否是隐私笔记")
    @NotNull(message = "是否是隐私笔记不能为空")
    private Boolean isPrivate;


    @ApiModelProperty("记录笔记时视频播放的时间点，单位：秒")
    private Integer noteMoment;

    @ApiModelProperty("课程id")
    @NotNull(message = "课程id不能为空")
    private Long courseId;

    @ApiModelProperty("章id")
    @NotNull(message = "章id不能为空")
    private Long chapterId;

    @ApiModelProperty("小节id")
    @NotNull(message = "小节id不能为空")
    private Long sectionId;
}