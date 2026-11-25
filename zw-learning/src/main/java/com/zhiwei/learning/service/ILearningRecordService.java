package com.zhiwei.learning.service;

import com.zhiwei.api.dto.leanring.LearningLessonDTO;
import com.zhiwei.learning.domian.dto.LearningRecordFormDTO;
import com.zhiwei.learning.domian.po.LearningRecord;
import com.zhiwei.learning.domian.vo.LearningSummaryVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 学习记录表 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-06
 */
public interface ILearningRecordService extends IService<LearningRecord> {
    // 查询指定课程的学习记录
    LearningLessonDTO queryLearningRecordByCourse(Long courseId);

    //     // 添加学习记录
    void addLearningRecord(LearningRecordFormDTO learningRecordFormDTO);

    /**
     * 学习活跃度按天聚合（概览页本周节奏、报告页热力图/指标卡）。
     *
     * @param days 统计天数（1-90，默认 30）
     * @return 缺失日期已补 0 的按天聚合结果
     */
    LearningSummaryVO learningSummary(Integer days);
}
