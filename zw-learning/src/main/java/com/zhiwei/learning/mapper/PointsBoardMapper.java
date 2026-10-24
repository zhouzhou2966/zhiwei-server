package com.zhiwei.learning.mapper;

import com.zhiwei.learning.domian.po.PointsBoard;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 学霸天梯榜 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
public interface PointsBoardMapper extends BaseMapper<PointsBoard> {

    void createPointsBoardTableBySeason(@Param("tableName") String tableName);

    List<PointsBoard> queryHistoryBoardBySeason(@Param("tableName") String tableName);

    /**
     * 判断分表是否存在。
     * 为什么要它：历史榜按赛季查 `points_board_{season}`，而分表只有「那个赛季有榜单数据」时
     * 才会被 savePointsBoardToDb 任务建出来。赛季表里有赛季、分表却没有是常态，
     * 直接 select 不存在的表会抛 SQLSyntaxErrorException → 接口 500「服务器内部异常」，
     * 前端只能显示「加载失败 + 重新加载」，其实这只是「这个赛季没有数据」。
     */
    int countTableByName(@Param("tableName") String tableName);
}
