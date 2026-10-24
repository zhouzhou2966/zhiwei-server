package com.zhiwei.learning.domian.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "笔记表单信息")
public class NoteFormDTO {

    @Schema(description = "笔记内容")
    @NotNull(message = "笔记内容不能为空")
    private String content;

    @Schema(description = "是否是隐私笔记")
    @NotNull(message = "是否是隐私笔记不能为空")
    private Boolean isPrivate;


    @Schema(description = "记录笔记时视频播放的时间点，单位：秒")
    private Integer noteMoment;

    @Schema(description = "课程id")
    @NotNull(message = "课程id不能为空")
    private Long courseId;

    @Schema(description = "章id")
    @NotNull(message = "章id不能为空")
    private Long chapterId;

    @Schema(description = "小节id")
    @NotNull(message = "小节id不能为空")
    private Long sectionId;
}