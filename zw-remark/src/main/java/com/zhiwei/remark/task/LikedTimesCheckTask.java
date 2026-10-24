package com.zhiwei.remark.task;

import com.zhiwei.remark.config.LikeProperties;
import com.zhiwei.remark.service.impl.LikedRecordServiceRedisImpl;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 点赞数变更推送任务
 * <p>
 * 从脏标记集合读取有点赞数变更的 bizId，批量查询 ZSet 最新计数，通过 MQ 通知下游（如学习服务）
 * 更新业务表的 liked_times。由 XXL-JOB 调度中心统一调度，默认每 20 秒执行一次。
 * <p>
 * 可靠性：采用"先读后删"策略——MQ 发送成功后才从脏标记集合移除，失败则保留等待下次重试。
 *
 * @author author
 * @since 2026-05-13
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LikedTimesCheckTask {

    private final LikeProperties likeProperties;
    private final LikedRecordServiceRedisImpl recordService;

    /**
     * 从脏标记集合读取变更 bizId，批量发送 MQ 通知下游更新 liked_times
     * <p>
     * XXL-JOB 任务名：likeTimesPushJob
     * 建议调度周期：20 秒（Cron: 0/20 * * * * ?）
     * 路由策略：第一个（单机执行即可，任务本身轻量无需分片）
     */
    @XxlJob("likeTimesPushJob")
    public void checkLikedTimes() {
        List<String> bizTypes = likeProperties.getBizTypes();
        for (String bizType : bizTypes) {
            try {
                recordService.readLikedTimesAndSendMessage(bizType);
            } catch (Exception e) {
                log.error("推送点赞数失败, bizType={}", bizType, e);
            }
        }
    }
}