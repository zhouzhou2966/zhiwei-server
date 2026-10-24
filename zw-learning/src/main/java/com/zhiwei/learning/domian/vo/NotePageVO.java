package com.zhiwei.learning.domian.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel(description = "笔记信息")
public class NotePageVO {

    @ApiModelProperty("笔记id")
    private Long id;

    @ApiModelProperty("笔记内容")
    private String content;

    @ApiModelProperty("笔记记录时的视频时间点，单位秒")
    private Integer noteMoment;

    @ApiModelProperty("是否是隐私笔记")
    private Boolean isPrivate;

    @ApiModelProperty("是否是采集的笔记")
    private Boolean isGathered;

    @ApiModelProperty("作者id")
    private Long authorId;

    @ApiModelProperty("作者名称")
    private String authorName;

    @ApiModelProperty("作者头像")
    private String authorIcon;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}