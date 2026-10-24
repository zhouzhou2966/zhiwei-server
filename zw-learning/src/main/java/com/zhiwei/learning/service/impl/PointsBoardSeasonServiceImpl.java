package com.zhiwei.learning.service.impl;

import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.learning.domian.po.PointsBoardSeason;
import com.zhiwei.learning.domian.vo.PointsBoardSeasonVO;
import com.zhiwei.learning.mapper.PointsBoardSeasonMapper;
import com.zhiwei.learning.service.IPointsBoardSeasonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-19
 */
@Service
public class PointsBoardSeasonServiceImpl extends ServiceImpl<PointsBoardSeasonMapper, PointsBoardSeason> implements IPointsBoardSeasonService {

    @Override
    public List<PointsBoardSeasonVO> getSeasonsList() {
        List<PointsBoardSeason> list = query().list();
        if (CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        return BeanUtils.copyList(list, PointsBoardSeasonVO.class);
    }


    @Override
    public Integer querySeasonByTime(LocalDateTime time) {
        Optional<PointsBoardSeason> season = lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .oneOpt();
        return season.map(PointsBoardSeason::getId).orElse(null);
    }
}
