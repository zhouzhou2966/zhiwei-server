package com.zhiwei.learning.domian.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "笔记更新表单")
public class NoteUpdateDTO {

    @Schema(description = "笔记内容")
    @NotNull(message = "笔记内容不能为空")
    private String content;
    @Schema(description = "是否是隐私笔记")
    @NotNull(message = "是否是隐私笔记不能为空")
    private Boolean isPrivate;
}