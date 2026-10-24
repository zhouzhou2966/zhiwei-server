package com.zhiwei.learning.domian.query;

import com.zhiwei.common.domain.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@ApiModel(description = "笔记分页查询条件")
public class NotePageQuery extends PageQuery {

    @ApiModelProperty(value = "课程id")
    private Long courseId;

    @ApiModelProperty(value = "小节id")
    private Long sectionId;

    @ApiModelProperty(value = "是否只查询我的笔记")
    private Boolean onlyMine;
}