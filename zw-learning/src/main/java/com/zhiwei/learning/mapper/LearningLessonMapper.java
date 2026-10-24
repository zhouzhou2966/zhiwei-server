package com.zhiwei.learning.mapper;

import com.zhiwei.learning.domian.po.LearningLesson;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import feign.Param;

/**
 * <p>
 * 学生课程表 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
public interface LearningLessonMapper extends BaseMapper<LearningLesson> {


/**
 * 查询用户计划总数的方法
 * @param userId 用户ID，用于指定要查询的用户
 * @return 返回该用户的计划总数，类型为Long
 */
    Long queryTotalPlan(@Param("userId") Long userId);
}
