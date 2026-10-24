package com.zhiwei.learning.handler;

import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.learning.Utils.TableInfoContext;
import com.zhiwei.learning.domian.po.PointsBoardSeason;
import com.zhiwei.learning.domian.po.PointsRecord;
import com.zhiwei.learning.service.impl.PointsBoardSeasonServiceImpl;
import com.zhiwei.learning.service.impl.PointsRecordServiceImpl;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PointsRecordPersistentHandler {
    private final PointsRecordServiceImpl pointsRecordService;
    private final PointsBoardSeasonServiceImpl pointsBoardSeasonService;

    @XxlJob("createPointsRecordTableJob")
    public void createPointsRecordTableOfLastSeason() {
        LocalDate lastMonth = LocalDate.now().minusMonths(1);
        // 查询上个月对应的赛季
        Integer season = pointsBoardSeasonService.querySeasonByTime(lastMonth.atStartOfDay());
        if (season == null) {
            log.warn("未找到上个月对应的赛季，跳过建表: {}", lastMonth);
            return;
        }

        String tableName = "points_record_" + season;
        log.info("为上个月赛季创建积分明细表: season={}, table={}", season, tableName);
        try {
            pointsRecordService.createPointsRecordTableBySeason(season);
            log.info("建表成功: {}", tableName);
        } catch (Exception e) {
            log.warn("建表失败（可能已存在）: {}", tableName, e);
        }
    }

    @XxlJob("savePointsRecordToDb")
    public void savePointsRecordToDb() {
        LocalDate lastMonth = LocalDate.now().minusMonths(1);

        // 查询上个月对应的赛季
        Integer season = pointsBoardSeasonService.querySeasonByTime(lastMonth.atStartOfDay());
        if (season == null) {
            log.warn("未找到上个月对应的赛季，跳过数据迁移: {}", lastMonth);
            return;
        }

        // 获取赛季的准确时间范围
        PointsBoardSeason seasonInfo = pointsBoardSeasonService.getById(season);
        if (seasonInfo == null) {
            log.warn("赛季信息不存在: season={}", season);
            return;
        }

        migrateSeasonData(seasonInfo);
    }

    private void migrateSeasonData(PointsBoardSeason season) {
        Integer seasonId = season.getId();
        String tableName = "points_record_" + seasonId;
        LocalDateTime beginTime = season.getBeginTime().atStartOfDay();
        LocalDateTime endTime = season.getEndTime().atTime(23, 59, 59);
        log.info("开始迁移赛季积分明细数据: season={}, table={}, begin={}, end={}",
                 seasonId, tableName, beginTime, endTime);

        int index = XxlJobHelper.getShardIndex();
        int total = XxlJobHelper.getShardTotal();

        try {
            TableInfoContext.setInfo(tableName);
            int pageNo = index + 1;
            int pageSize = 200;
            int totalCount = 0;
            List<Long> migratedIds = new ArrayList<>();

            while (true) {
                List<PointsRecord> list = pointsRecordService.lambdaQuery()
                        .ge(PointsRecord::getCreateTime, beginTime)
                        .le(PointsRecord::getCreateTime, endTime)
                        .last("LIMIT " + ((pageNo - 1) * pageSize) + ", " + pageSize)
                        .list();

                if (CollUtils.isEmpty(list)) {
                    break;
                }

                for (PointsRecord r : list) {
                    migratedIds.add(r.getId());
                    r.setId(null);
                }
                pointsRecordService.saveBatch(list);
                totalCount += list.size();
                pageNo += total;
            }

            if (!migratedIds.isEmpty()) {
                pointsRecordService.removeByIds(migratedIds);
                log.info("从主表删除已迁移数据: season={}, 删除数量={}", seasonId, migratedIds.size());
            }

            log.info("赛季数据迁移完成: season={}, 迁移记录数={}", seasonId, totalCount);
        } catch (Exception e) {
            log.error("赛季数据迁移失败: season={}", seasonId, e);
            throw e;
        } finally {
            TableInfoContext.clear();
        }
    }
}
