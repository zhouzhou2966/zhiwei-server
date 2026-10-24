package com.zhiwei.learning.domian.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "管理端笔记详情信息")
public class NoteAdminDetailVO {

    @Schema(description = "笔记id")
    private Long id;

    @Schema(description = "课程名称")
    private String courseName;

    @Schema(description = "章名称")
    private String chapterName;

    @Schema(description = "节名称")
    private String sectionName;

    @Schema(description = "多级分类名称，用/拼接")
    private String categoryNames;

    @Schema(description = "笔记内容")
    private String content;

    @Schema(description = "笔记记录时的视频时间点，单位秒")
    private Integer noteMoment;

    @Schema(description = "是否在用户端隐藏")
    private Boolean hidden;

    @Schema(description = "被采集次数")
    private int usedTimes;

    @Schema(description = "作者名称")
    private String authorName;

    @Schema(description = "作者电话")
    private String authorPhone;

    @Schema(description = "采集人集合")
    private List<String> gathers;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}