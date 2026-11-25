package com.zhiwei.learning.domian.vo;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 学习活跃度按天聚合结果（GET /ls/learning-records/summary）。
 *
 * 服务于学习概览页的「本周节奏」柱状图和报告页的热力图 / 指标卡。
 * 缺失的日期由 Service 补 0 —— 前端不做补全（柱状图缺柱、热力图错位都是补全职责漏掉的症状）。
 */
@Data
public class LearningSummaryVO {

    /** 实际统计的天数（入参 days 兜底后的值） */
    private Integer days;

    /** 区间内学习过的小节数（去重：同一天内、同一小节只算一次） */
    private Long totalSections;

    /** 活跃天数：至少有 1 条学习记录的天数 */
    private Integer activeDays;

    /** 按天聚合（区间内每一天都有，没学的补 0），date 升序 */
    private List<LearningDailyStatVO> daily;
}
