package com.zhiwei.learning.service;

import com.zhiwei.learning.domian.po.PointsBoard;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.learning.domian.query.PointsBoardQuery;
import com.zhiwei.learning.domian.vo.PointsBoardVO;

import java.util.List;

/**
 * <p>
 * 学霸天梯榜 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
public interface IPointsBoardService extends IService<PointsBoard> {
    // 分页查询指定赛季积分排行榜
    PointsBoardVO queryPointsBoardBySeason(PointsBoardQuery query);

    // 查询当前赛季积分排行榜
    List<PointsBoard> queryCurrentBoardList(String key, int pageNo, int pageSize);

    // 创建指定赛季积分榜表
    void createPointsBoardTableBySeason(Integer season);
}
