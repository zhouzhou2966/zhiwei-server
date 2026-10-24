package com.zhiwei.learning.domian.query;

import com.zhiwei.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)  // 继承PageQuery的equals和hashCode方法
@Data
@Schema(description = "互动问题分页查询条件")
public class QuestionPageQuery extends PageQuery {
    // 用户端查询条件
    @Schema(description = "课程id")
    private Long courseId;
    @Schema(description = "小节id", example = "1")
    private Long sectionId;
    @Schema(description = "是否只查询我的问题", example = "1")
    private Boolean onlyMine;
}
