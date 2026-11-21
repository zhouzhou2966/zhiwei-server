package com.zhiwei.learning.domian.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "笔记信息")
public class NotePageVO {

    @Schema(description = "笔记id")
    private Long id;

    @Schema(description = "笔记内容")
    private String content;

    @Schema(description = "笔记记录时的视频时间点，单位秒")
    private Integer noteMoment;

    @Schema(description = "课程id（学习中心笔记页回跳用）")
    private Long courseId;

    @Schema(description = "小节id（学习中心笔记页回跳用）")
    private Long sectionId;

    @Schema(description = "课程名称（列表展示用，分类服务批量补齐）")
    private String courseName;

    @Schema(description = "是否是隐私笔记")
    private Boolean isPrivate;

    @Schema(description = "是否是采集的笔记")
    private Boolean isGathered;

    @Schema(description = "作者id")
    private Long authorId;

    @Schema(description = "作者名称")
    private String authorName;

    @Schema(description = "作者头像")
    private String authorIcon;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}