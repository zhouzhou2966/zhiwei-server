package com.zhiwei.learning.service.impl;

import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.DateUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.learning.constants.RedisConstants;
import com.zhiwei.learning.domian.enums.PointsRecordType;
import com.zhiwei.learning.domian.po.PointsRecord;
import com.zhiwei.learning.domian.vo.PointsStatisticsVO;
import com.zhiwei.learning.mapper.PointsRecordMapper;
import com.zhiwei.learning.service.IPointsRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {
    private final PointsRecordMapper pointsRecordMapper;
    private final StringRedisTemplate redisTemplate;
    private final PointsBoardSeasonServiceImpl pointsBoardSeasonService;

    @Override
    public void addPointsRecord(Long userId, int points, PointsRecordType type) {
        if (userId == null || points <= 0 || type == null) {
            return;
        }
        int maxPoints = type.getMaxPoints();
        int realPoints = points;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = DateUtils.getDayStartTime(now);
        LocalDateTime end = DateUtils.getDayEndTime(now);

        if (maxPoints > 0) {
            int currPoints = pointsRecordMapper.sumPoint(userId, type, begin, end);
            if (currPoints >= maxPoints) {
                return;
            }
            if (currPoints + points > maxPoints) {
                realPoints = maxPoints - currPoints;
            }
        }
        PointsRecord p = new PointsRecord();
        p.setUserId(userId);
        p.setPoints(realPoints);
        p.setType(type);
        save(p);
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        redisTemplate.opsForZSet().incrementScore(key, userId.toString(), realPoints);


    }

    @Override
    public List<PointsStatisticsVO> queryTodayPoints() {
        Long userId = UserContext.getUserId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = DateUtils.getDayStartTime(now);
        LocalDateTime end = DateUtils.getDayEndTime(now);
        List<PointsRecord> list = pointsRecordMapper.sumPoints(userId, begin, end);
        if (CollUtils.isEmpty(list)) {
            return null;
        }
        List<PointsStatisticsVO> voList = list.stream().map(p -> {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setPoints(p.getPoints());
            vo.setType(p.getType().getDesc());
            vo.setMaxPoints(p.getType().getMaxPoints());
            return vo;
        }).collect(Collectors.toList());
        return voList;
    }


    @Override
    public void createPointsRecordTableBySeason(Integer season) {
        pointsRecordMapper.createPointsRecordTableBySeason("points_record_" + season);
    }


    @Override
    public List<PointsRecord> queryPointsRecordList(LocalDateTime beginTime, LocalDateTime endTime, int pageNo, int pageSize) {
        if (beginTime == null || endTime == null) {
            return CollUtils.emptyList();
        }
        int offset = (pageNo - 1) * pageSize;
        return pointsRecordMapper.queryPointsRecordList(beginTime, endTime, offset, pageSize);
    }

    @Override
    public Integer queryWeekPoints(Long userId, LocalDateTime begin, LocalDateTime end) {
        if (userId == null || begin == null || end == null) {
            return 0;
        }
        // ① 主表：当前月份的数据都还在这
        Integer total = pointsRecordMapper.sumWeekPoints(userId, begin, end);
        int points = total == null ? 0 : total;

        // ② 跨月周补分表：月初的 savePointsRecordToDb 会把上个月整月搬到 points_record_{season}
        //    并从主表删除，所以「本周一落在上个月」的那几天在主表里已经查不到了。
        //    用「周一 vs 周日」判跨月，而不是拿 begin 和 end 比 —— end 是周日 23:59:59，
        //    月末那周 end 会落到下个月，拿它比会误判成跨月（虽然无害，但多一次无谓查询）。
        LocalDate monday = begin.toLocalDate();
        LocalDate sunday = monday.plusDays(6);
        boolean crossMonth = monday.getYear() != sunday.getYear()
                || monday.getMonthValue() != sunday.getMonthValue();
        if (!crossMonth) {
            return points;
        }

        Integer season = pointsBoardSeasonService.querySeasonByTime(begin);
        if (season == null) {
            return points;
        }
        String tableName = "points_record_" + season;
        if (pointsRecordMapper.countTableByName(tableName) == 0) {
            // 分表还没建（月初任务没跑）⇒ 上月数据仍在主表，① 已经算过，不能再加
            return points;
        }

        // 借显式表名参数打到分表（不依赖动态表名插件解析 SQL，行为更确定）
        Integer lastMonthPoints = pointsRecordMapper.sumPointsByTable(tableName, userId, begin, end);
        if (lastMonthPoints != null) {
            points += lastMonthPoints;
        }
        return points;
    }
}
