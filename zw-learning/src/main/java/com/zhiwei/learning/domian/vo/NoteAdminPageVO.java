package com.zhiwei.learning.domian.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "笔记管理端信息")
public class NoteAdminPageVO {

    @Schema(description = "笔记id")
    private Long id;

    @Schema(description = "课程名称")
    private String courseName;

    @Schema(description = "章名称")
    private String chapterName;

    @Schema(description = "节名称")
    private String sectionName;

    @Schema(description = "笔记内容")
    private String content;

    @Schema(description = "是否在用户端隐藏")
    private Boolean hidden;

    @Schema(description = "被采集次数")
    private int usedTimes;

    @Schema(description = "作者名称")
    private String authorName;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}