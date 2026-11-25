package com.zhiwei.exam.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 我的考试 · 全量统计
 * <p>
 * 为什么单独一个接口：列表已经改成后端分页（一页 10 条），
 * 「考试总数 / 均分 / 最高分」必须基于<b>全部</b>记录算 ——
 * 拿当前页凑数会随翻页变化，是最容易糊弄也最容易被用户发现的那种假数据。
 * 原来前端一次拉 100 条硬凑，超过 100 次考试统计就开始失真。
 * </p>
 */
@Data
@Schema(description = "我的考试全量统计")
public class ExamStatsVO {

    @Schema(description = "已完成考试总次数")
    private Long total;

    @Schema(description = "平均分（保留一位小数；没有已判分记录时为 null）")
    private Double avgScore;

    @Schema(description = "最高分（没有已判分记录时为 null）")
    private Integer maxScore;

    @Schema(description = "最高分那次的交卷日期，格式 M/d，例如 10/22")
    private String maxWhen;

    @Schema(description = "最近一次交卷日期，格式 M/d")
    private String lastWhen;
}
