package com.zhiwei.learning.domian.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
@ApiModel(description = "笔记更新表单")
public class NoteUpdateDTO {

    @ApiModelProperty("笔记内容")
    @NotNull(message = "笔记内容不能为空")
    private String content;
    @ApiModelProperty("是否是隐私笔记")
    @NotNull(message = "是否是隐私笔记不能为空")
    private Boolean isPrivate;
}