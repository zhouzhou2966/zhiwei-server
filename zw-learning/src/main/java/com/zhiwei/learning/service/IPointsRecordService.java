package com.zhiwei.learning.service;

import com.zhiwei.learning.domian.enums.PointsRecordType;
import com.zhiwei.learning.domian.po.PointsRecord;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.learning.domian.vo.PointsStatisticsVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
public interface IPointsRecordService extends IService<PointsRecord> {
    // 添加积分记录
    void addPointsRecord(Long userId, int points, PointsRecordType type);

    // 查询积分统计
    List<PointsStatisticsVO> queryTodayPoints();

    // 根据赛季创建积分表
    void createPointsRecordTableBySeason(Integer season);

    // 查询指定时间内积分记录
    List<PointsRecord> queryPointsRecordList(LocalDateTime beginTime, LocalDateTime endTime, int pageNo, int pageSize);

    /**
     * 查询用户在指定时间范围内获得的总积分，含跨月时的上月分表。
     * 个人中心「学习概览 - 本周积分」用。
     *
     * @param begin 起始时间（含）
     * @param end   结束时间（含）
     * @return 积分总和，无记录时为 0
     */
    Integer queryWeekPoints(Long userId, LocalDateTime begin, LocalDateTime end);
}
