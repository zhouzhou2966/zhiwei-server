package com.zhiwei.learning.mapper;

import com.zhiwei.learning.domian.enums.PointsRecordType;
import com.zhiwei.learning.domian.po.PointsRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiwei.learning.domian.vo.PointsStatisticsVO;
import org.apache.ibatis.annotations.MapKey;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 学习积分记录，每个月底清零 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
public interface PointsRecordMapper extends BaseMapper<PointsRecord> {

    int sumPoint(Long userId, PointsRecordType type, LocalDateTime begin, LocalDateTime end);

    /**
     * 统计用户在指定时间范围内获得的总积分（不分类型）。
     * 个人中心「学习概览 - 本周积分」用，begin/end 传本周的起止时间。
     *
     * @return 积分总和，无记录时返回 0（SQL 里 COALESCE 兜住）
     */
    Integer sumWeekPoints(Long userId, LocalDateTime begin, LocalDateTime end);

    /**
     * 按表名统计积分（主表 points_record 或历史分表 points_record_{season}）。
     * 为什么不复用 sumWeekPoints：分表名是运行时拼的，用 `${tableName}` 显式传比依赖
     * MyBatis-Plus 动态表名插件去解析 SQL 更确定。表名只由 "points_record_" + 数字 拼成，
     * 不接受外部输入，不存在注入面。
     */
    Integer sumPointsByTable(String tableName, Long userId, LocalDateTime begin, LocalDateTime end);

    /**
     * 判断分表是否存在（如 points_record_9）。
     * 为什么要它：月初 savePointsRecordToDb 任务才会把上月数据搬到分表并建表，
     * 直接 select 不存在的表会抛 SQLSyntaxErrorException，把「本周积分」整个接口带崩。
     */
    int countTableByName(String tableName);


    List<PointsRecord> sumPoints(Long userId, LocalDateTime begin, LocalDateTime end);

    void createPointsRecordTableBySeason(String tableName);

    List<PointsRecord> queryPointsRecordList(LocalDateTime beginTime, LocalDateTime endTime, int offset, int pageSize);
}
