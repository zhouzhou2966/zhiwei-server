package com.zhiwei.learning.domian.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel(description = "笔记管理端信息")
public class NoteAdminPageVO {

    @ApiModelProperty("笔记id")
    private Long id;

    @ApiModelProperty("课程名称")
    private String courseName;

    @ApiModelProperty("章名称")
    private String chapterName;

    @ApiModelProperty("节名称")
    private String sectionName;

    @ApiModelProperty("笔记内容")
    private String content;

    @ApiModelProperty("是否在用户端隐藏")
    private Boolean hidden;

    @ApiModelProperty("被采集次数")
    private int usedTimes;

    @ApiModelProperty("作者名称")
    private String authorName;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}