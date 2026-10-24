package com.zhiwei.learning.domian.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@ApiModel(description = "管理端笔记详情信息")
public class NoteAdminDetailVO {

    @ApiModelProperty("笔记id")
    private Long id;

    @ApiModelProperty("课程名称")
    private String courseName;

    @ApiModelProperty("章名称")
    private String chapterName;

    @ApiModelProperty("节名称")
    private String sectionName;

    @ApiModelProperty("多级分类名称，用/拼接")
    private String categoryNames;

    @ApiModelProperty("笔记内容")
    private String content;

    @ApiModelProperty("笔记记录时的视频时间点，单位秒")
    private Integer noteMoment;

    @ApiModelProperty("是否在用户端隐藏")
    private Boolean hidden;

    @ApiModelProperty("被采集次数")
    private int usedTimes;

    @ApiModelProperty("作者名称")
    private String authorName;

    @ApiModelProperty("作者电话")
    private String authorPhone;

    @ApiModelProperty("采集人集合")
    private List<String> gathers;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}