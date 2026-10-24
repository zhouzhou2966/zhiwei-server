package com.zhiwei.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.learning.domian.vo.PointsStatisticsVO;
import com.zhiwei.learning.domian.vo.SignResultVO;
import lombok.RequiredArgsConstructor;

import java.util.List;

public interface ISignRecordService {
    // 签到
    SignResultVO addSignRecord();

    // 查询签到记录
    List<Integer> querySignRecord();



}
