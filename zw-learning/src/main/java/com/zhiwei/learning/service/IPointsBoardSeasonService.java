package com.zhiwei.learning.service;

import com.zhiwei.learning.domian.po.PointsBoardSeason;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.learning.domian.vo.PointsBoardSeasonVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
public interface IPointsBoardSeasonService extends IService<PointsBoardSeason> {
    // 查询赛季列表
    List<PointsBoardSeasonVO> getSeasonsList();
    // 根据时间查询赛季
    Integer querySeasonByTime(LocalDateTime time);
}
