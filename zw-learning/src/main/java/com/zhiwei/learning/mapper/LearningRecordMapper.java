package com.zhiwei.learning.mapper;

import com.zhiwei.learning.domian.po.LearningRecord;
import com.zhiwei.learning.domian.vo.LearningDailyStatVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 学习记录表 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-06
 */
public interface LearningRecordMapper extends BaseMapper<LearningRecord> {

    /**
     * 按「天」聚合学习记录（概览页本周节奏、报告页热力图的底表）。
     *
     * ⚠️ finished 是 bit(1)，比较要用 b'1'；同一天反复看同一小节由
     * COUNT(DISTINCT section_id) 去重 —— moment 是播放位置不是时长，这里不做任何时长统计。
     * 走 idx_user_update(user_id, update_time) 联合索引。
     */
    @Select("SELECT DATE(update_time) AS statDate, " +
            "COUNT(DISTINCT section_id) AS sections, " +
            "SUM(CASE WHEN finished = b'1' THEN 1 ELSE 0 END) AS finished " +
            "FROM learning_record " +
            "WHERE user_id = #{userId} AND update_time >= #{begin} " +
            "GROUP BY DATE(update_time) " +
            "ORDER BY statDate")
    List<LearningDailyStatVO> selectDailyStats(@Param("userId") Long userId, @Param("begin") LocalDateTime begin);

    /**
     * 区间内学习过的小节总数（跨天去重：同一小节在两天都看过只算一次）。
     * 不能用「每日 sections 求和」代替 —— 那会把跨天重复学习的同一小节算成两次。
     */
    @Select("SELECT COUNT(DISTINCT section_id) FROM learning_record " +
            "WHERE user_id = #{userId} AND update_time >= #{begin}")
    Long selectTotalSections(@Param("userId") Long userId, @Param("begin") LocalDateTime begin);
}
