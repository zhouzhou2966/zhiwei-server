package com.zhiwei.learning.domian.vo;

import lombok.Data;

import java.time.LocalDate;

/**
 * 单日学习统计（learning_record 按 DATE(update_time) 聚合的行）。
 *
 * 独立成类而不是塞进 LearningSummaryVO 的内部类：它同时是 Mapper 的返回类型，
 * MyBatis 映射内部类要写全限定名，单独一个文件省事。
 */
@Data
public class LearningDailyStatVO {

    /** 统计日期 */
    private LocalDate statDate;

    /** 当日学习过的小节数（DISTINCT section_id：同一天反复看同一小节只算一次） */
    private Long sections;

    /** 当日完成的小节数（finished = true 的记录数） */
    private Long finished;
}
