package com.zhiwei.remark.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.remark.domian.po.LikedRecord;
import com.zhiwei.remark.mapper.LikedRecordMapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.zhiwei.remark.constants.RedisConstants.LIKES_BIZ_KEY_PREFIX;
import static com.zhiwei.remark.constants.RedisConstants.LIKES_SYNC_FAILED_KEY_PREFIX;

/**
 * 点赞记录持久化补偿任务
 * <p>
 * 当 MQ 持久化事件发送失败（重试耗尽）时，likeOrUnlike 会将失败的 userId
 * 写入 Redis Set（likes:sync:failed:{bizType}:{bizId}），由本任务每日凌晨自动补偿。
 * <p>
 * 补偿逻辑：以 Redis 当前点赞状态为准
 * <ul>
 *   <li>SISMEMBER → true  → INSERT IGNORE（用户当前在点赞，落库）</li>
 *   <li>SISMEMBER → false → DELETE（用户当前不在点赞，删库）</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>凌晨 3 点执行，避开业务高峰</li>
 *   <li>SCAN 替代 KEYS，非阻塞遍历</li>
 *   <li>Pipeline 批量 SISMEMBER，一次网络往返</li>
 *   <li>处理成功后 DEL key，失败保留 key 下次重试</li>
 * </ul>
 *
 * @author author
 * @since 2026-08-28
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LikedRecordSyncCompensateTask {

    private final StringRedisTemplate redisTemplate;
    private final LikedRecordMapper recordMapper;

    /**
     * 每日补偿 MQ 发送失败的点赞记录持久化事件
     * <p>
     * XXL-JOB 任务名：likeSyncCompensateJob
     * 建议调度周期：每天凌晨 3 点（Cron: 0 0 3 * * ?）
     * 路由策略：第一个（单机执行，无需分片）
     */
    @XxlJob("likeSyncCompensateJob")
    public void compensateFailedSync() {
        Set<String> keys = scanKeys(LIKES_SYNC_FAILED_KEY_PREFIX + "*");
        if (CollUtils.isEmpty(keys)) {
            return;
        }
        log.info("开始补偿持久化失败记录，共 {} 个 key", keys.size());

        for (String key : keys) {
            try {
                // 1. 解析 bizType 和 bizId
                String suffix = key.substring(LIKES_SYNC_FAILED_KEY_PREFIX.length());
                String[] parts = suffix.split(":", 2);
                if (parts.length != 2) {
                    redisTemplate.delete(key);
                    log.warn("非法 key 已清理：{}", key);
                    continue;
                }
                String bizType = parts[0];
                Long bizId = Long.valueOf(parts[1]);

                // 2. 读取 Set 中的 userIds
                Set<String> userIds = redisTemplate.opsForSet().members(key);
                if (CollUtils.isEmpty(userIds)) {
                    redisTemplate.delete(key);
                    continue;
                }
                List<String> userIdList = new ArrayList<>(userIds);

                // 3. Pipeline 批量 SISMEMBER，一次网络往返查询所有 userId 的当前点赞状态
                String likeKey = LIKES_BIZ_KEY_PREFIX + bizType + ":" + bizId;
                List<Object> results = redisTemplate.executePipelined(
                        (RedisCallback<Object>) connection -> {
                            StringRedisConnection src = (StringRedisConnection) connection;
                            for (String uid : userIdList) {
                                src.sIsMember(likeKey, uid);
                            }
                            return null;
                        }
                );

                // 4. 以 Redis 当前状态为准：在点赞集合 → INSERT，不在 → DELETE
                List<LikedRecord> toInsert = new ArrayList<>();
                List<Long> toDelete = new ArrayList<>();
                for (int i = 0; i < userIdList.size(); i++) {
                    Long uid = Long.valueOf(userIdList.get(i));
                    if (Boolean.TRUE.equals(results.get(i))) {
                        toInsert.add(new LikedRecord()
                                .setUserId(uid)
                                .setBizId(bizId)
                                .setBizType(bizType));
                    } else {
                        toDelete.add(uid);
                    }
                }

                if (!toInsert.isEmpty()) {
                    recordMapper.insertIgnoreBatch(toInsert);
                }
                if (!toDelete.isEmpty()) {
                    recordMapper.delete(new LambdaQueryWrapper<LikedRecord>()
                            .in(LikedRecord::getUserId, toDelete)
                            .eq(LikedRecord::getBizId, bizId)
                            .eq(LikedRecord::getBizType, bizType));
                }

                // 5. 处理成功，删除 key
                redisTemplate.delete(key);
                log.debug("补偿成功，key={}, insert={}, delete={}", key, toInsert.size(), toDelete.size());
            } catch (Exception e) {
                log.error("补偿处理失败，key 保留待下次重试：{}", key, e);
            }
        }
    }

    /**
     * 使用 SCAN 命令遍历匹配的 Redis key（非阻塞，替代 KEYS）
     *
     * @param pattern 匹配模式，如 "likes:sync:failed:*"
     * @return 匹配的 key 集合
     */
    private Set<String> scanKeys(String pattern) {
        return redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> keySet = new HashSet<>();
            Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions().match(pattern).count(100).build());
            while (cursor.hasNext()) {
                keySet.add(new String(cursor.next()));
            }
            return keySet;
        });
    }
}