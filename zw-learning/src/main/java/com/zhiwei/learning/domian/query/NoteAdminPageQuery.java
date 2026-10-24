package com.zhiwei.learning.domian.query;

import com.zhiwei.common.domain.query.PageQuery;
import com.zhiwei.common.utils.DateUtils;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
@ApiModel(description = "笔记管理端分页查询条件")
public class NoteAdminPageQuery extends PageQuery {

    @ApiModelProperty(value = "课程名称关键字", example = "Redis")
    private String name;

    @ApiModelProperty(value = "笔记状态，是否在用户端隐藏", example = "false")
    private Boolean hidden;

    @ApiModelProperty(value = "更新时间区间的开始时间", example = "2023-7-18 19:52:36")
    @DateTimeFormat(pattern = DateUtils.DEFAULT_DATE_TIME_FORMAT)
    private LocalDateTime beginTime;

    @ApiModelProperty(value = "更新时间区间的结束时间", example = "2023-7-18 19:52:36")
    @DateTimeFormat(pattern = DateUtils.DEFAULT_DATE_TIME_FORMAT)
    private LocalDateTime endTime;
}