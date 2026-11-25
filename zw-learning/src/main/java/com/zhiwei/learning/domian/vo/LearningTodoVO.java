package com.zhiwei.learning.domian.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习概览页的「待办 + 即将到期」（GET /ls/lessons/todo）。
 *
 * 只包含两类待办：本周计划缺口、即将到期课程。
 * 接口整体 try/catch，任何异常都返回空结构而不是抛出 —— 前端对空结构的处理是
 * 「显示保持节奏的文案」，不能因为一个装饰性接口把 learning 服务或概览页打挂。
 */
@Data
public class LearningTodoVO {

    /** 本周计划缺口（gap = max(0, weekTotalPlan - weekFinished)，gap 为 0 时前端不展示这条待办） */
    private PlanGap planGap;

    /** 7 天内到期的课程（最多 5 条，按到期时间升序；只含未学完的课） */
    private List<ExpiringCourse> expiring;

    @Data
    public static class PlanGap {
        private Integer weekFinished;
        private Integer weekTotalPlan;
        private Integer gap;
    }

    @Data
    public static class ExpiringCourse {
        private Long courseId;
        private String courseName;
        private LocalDateTime expireTime;
        /** 距到期还有几天（按自然日向上取整） */
        private Integer daysLeft;
    }
}
