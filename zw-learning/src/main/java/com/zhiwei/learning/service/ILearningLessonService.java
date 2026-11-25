package com.zhiwei.learning.service;

import com.zhiwei.api.dto.leanring.LearningLessonDTO;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.domain.query.PageQuery;
import com.zhiwei.learning.domian.dto.LearningPlanDTO;
import com.zhiwei.learning.domian.po.LearningLesson;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.learning.domian.vo.LearningLessonVO;
import com.zhiwei.learning.domian.vo.LearningPlanPageVO;
import com.zhiwei.learning.domian.vo.LearningTodoVO;

import jakarta.validation.Valid;
import java.util.List;

/**
 * <p>
 * 学生课程表 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
public interface ILearningLessonService extends IService<LearningLesson> {
    // 添加用户课程
    void addUserLessons(Long userId, List<Long> courseIds);

    // 查询用户课程
    PageDTO<LearningLessonVO> queryMyLessons(PageQuery pageQuery);

    //    删除用户课程
    void deleteMyLesson(Long courseId);

    //   批量删除已退款课程
    void deleteMyLessons(Long userId, List<Long> courseIds);

    //     查询用户课程是否有效
    Long isLessonValid(Long courseId);

    //     查询课程信息
    LearningLessonVO getLessonInfo(Long courseId);

    // 查询课程学习人数
    Long countLearningLessonByCourse(Long courseId);

    //   查询用户课程
    LearningLesson queryByUserIdAndCourseId(Long userId, Long courseId);

    // 创建学习计划
    void createLearningPlans(@Valid LearningPlanDTO learningPlanDTO);

    //  // 查询我的学习计划
    LearningPlanPageVO queryMyPlans(PageQuery pageQuery);

    //  查询正在学习课程
    LearningLessonVO getNowLesson();

    // 更新已过期课程状态
    void updateExpiredLessonsStatus();

    /**
     * 学习概览页的待办 + 即将到期（GET /ls/lessons/todo）。
     *
     * @return 任何异常都返回空结构（planGap 归零、expiring 空列表），不抛出
     */
    LearningTodoVO learningTodo();

}
