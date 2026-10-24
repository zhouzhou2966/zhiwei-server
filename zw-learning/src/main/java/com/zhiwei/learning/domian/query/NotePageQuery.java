package com.zhiwei.learning.domian.query;

import com.zhiwei.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "笔记分页查询条件")
public class NotePageQuery extends PageQuery {

    @Schema(description = "课程id")
    private Long courseId;

    @Schema(description = "小节id")
    private Long sectionId;

    @Schema(description = "是否只查询我的笔记")
    private Boolean onlyMine;
}