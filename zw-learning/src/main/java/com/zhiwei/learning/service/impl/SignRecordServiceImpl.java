package com.zhiwei.learning.service.impl;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.DateUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.learning.constants.RedisConstants;
import com.zhiwei.learning.domian.enums.PointsRecordType;
import com.zhiwei.learning.domian.po.PointsRecord;
import com.zhiwei.learning.domian.vo.PointsStatisticsVO;
import com.zhiwei.learning.domian.vo.SignResultVO;
import com.zhiwei.learning.mapper.PointsRecordMapper;
import com.zhiwei.learning.message.SignInMessage;
import com.zhiwei.learning.service.IPointsRecordService;
import com.zhiwei.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.zhiwei.learning.constants.RedisConstants.SIGN_RECORD_KEY_PREFIX;


@Service
@RequiredArgsConstructor
@Slf4j
public class SignRecordServiceImpl implements ISignRecordService {
    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper mqHelper;

    @Override
    public SignResultVO addSignRecord() {
        Long userId = UserContext.getUserId();

        LocalDate now = LocalDate.now();

        String key = SIGN_RECORD_KEY_PREFIX + userId + now.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        int offset = now.getDayOfMonth() - 1;

        Boolean exits = redisTemplate.opsForValue().setBit(key, offset, true);
        if (Boolean.TRUE.equals(exits)) {
            throw new BizIllegalException("已经签到过了");
        }

        int signDays = countSignDays(key, now.getDayOfMonth());

        //计算签到得分

        int rewardPoints = 0;

        switch (signDays) {
            case 7:
                rewardPoints = 10;
                break;
            case 14:
                rewardPoints = 20;
                break;
            case 28:
                rewardPoints = 40;
                break;
        }

        //保存积分明细记录
        try {
            mqHelper.send(
                    MqConstants.Exchange.LEARNING_EXCHANGE,
                    MqConstants.Key.SIGN_IN,
                    SignInMessage.of(userId, rewardPoints + 1));
        } catch (Exception e) {
            log.error("发送签到积分消息失败", e);
        }

        SignResultVO vo = new SignResultVO();
        vo.setSignDays(signDays);
        vo.setRewardPoints(rewardPoints);
        return vo;
    }

    private int countSignDays(String key, int len) {
        List<Long> result = redisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(
                        BitFieldSubCommands.BitFieldType.unsigned(len)).valueAt(0));
        if (CollUtils.isEmpty(result)) {
            return 0;
        }
        Long num = result.get(0);
        int count = 0;
        while ((num & 1) == 1) {
            count++;
            num = num >> 1;
        }
        return count;
    }


    @Override
    public List<Integer> querySignRecord() {
        Long userId = UserContext.getUserId();
        LocalDate now = LocalDate.now();
        String key = SIGN_RECORD_KEY_PREFIX + userId + now.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        int today = now.getDayOfMonth();
        List<Long> result = redisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(
                BitFieldSubCommands.BitFieldType.unsigned(today)).valueAt(0));
        if (CollUtils.isEmpty(result)) {
            return CollUtils.emptyList();
        }
        Long num = result.get(0);
        List<Integer> signList = new ArrayList<>(today);
        // 从第1天到今天，顺序正确，前端直接用
        for (int i = today - 1; i >= 0; i--) {
            signList.add((int) ((num >> i) & 1));
        }
        log.info("查询用户{}的签到记录：{}", userId, signList);

        return signList;
    }



}
