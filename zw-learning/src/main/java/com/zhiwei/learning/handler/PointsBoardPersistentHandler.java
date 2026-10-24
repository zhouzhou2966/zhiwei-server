package com.zhiwei.learning.handler;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.DateUtils;
import com.zhiwei.learning.Utils.TableInfoContext;
import com.zhiwei.learning.constants.RedisConstants;
import com.zhiwei.learning.domian.po.PointsBoard;
import com.zhiwei.learning.service.IPointsBoardSeasonService;
import com.zhiwei.learning.service.IPointsBoardService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor

public class PointsBoardPersistentHandler {
    private final IPointsBoardSeasonService pointsBoardSeasonService;
    private final IPointsBoardService pointsBoardService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 创建上赛季积分板表的任务方法
     * 使用XXL-JOB注解标记为定时任务，任务名为"createTableJob"
     *
     * @param
     * @return 无返回值
     */
    @XxlJob("createPointsBoardTableJob")
    public void createPointsBoardTableOfLastSeason() {  // 方法名：创建上赛季积分板表
        // 获取当前时间减去一个月的时间点
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 根据计算出的时间查询对应的赛季
        Integer season = pointsBoardSeasonService.querySeasonByTime(time);
        // 如果查询不到对应的赛季，则直接返回
        if (season == null) {
            return;
        }
        // 根据查询到的赛季创建积分板表
        pointsBoardService.createPointsBoardTableBySeason(season);

    }

    /**
     * 将积分榜数据保存到数据库的任务方法
     * 使用@XxlJob注解标记为XXL-JOB的任务
     * 任务名称为"savePointsBoardToDb"
     */
    @XxlJob("savePointsBoardToDb")
    public void savePointsBoardToDb() {
        // 获取一个月前的时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 构造Redis中积分榜数据的键
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        // 查询指定时间对应的赛季
        Integer season = pointsBoardSeasonService.querySeasonByTime(time);
        // 如果赛季不存在，直接返回
        if (season == null) {
            return;
        }
        // 设置数据库表信息上下文，根据赛季动态切换表
        TableInfoContext.setInfo("points_board_" + season);
        int index = XxlJobHelper.getShardIndex(); // 获取当前任务的索引，从0开始
        int total = XxlJobHelper.getShardTotal();   // 获取总任务数

        try {
            int pageNo = index + 1;
            int pageSize = 1000;
            while (true) {
                List<PointsBoard> boardList = pointsBoardService.queryCurrentBoardList(key, pageNo, pageSize);
                if (CollUtils.isEmpty(boardList)) {
                    break;
                }
                boardList.forEach(board -> {
                    board.setId(board.getRank().longValue());
                    board.setRank(null);
                });
                pointsBoardService.saveBatch(boardList);// 批量保存积分榜数据
                pageNo += total;
            }
        } finally {
            TableInfoContext.clear();
        }
    }


    /**
     * 清除一个月前的积分榜数据
     * 该方法通过XXL-JOB注解定时执行，用于清理过期的积分榜数据
     *
     * @XxlJob("clearPointsBoardFromRedis") XXL-JOB框架的注解，指定任务名为"clearPointsBoardFromRedis"
     */
    @XxlJob("clearPointsBoardFromRedis")
    public void clearPointsBoardFromRedis() {
        // 计算一个月前的时间点
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 构建一个月前积分榜对应的Redis键名
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        // 从Redis中删除对应的积分榜数据
        redisTemplate.unlink(key);
    }
}