package com.zhiwei.remark.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.api.dto.remark.LikeTimesDTO;
import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.remark.domian.dto.LikeRecordFormDTO;
import com.zhiwei.remark.domian.dto.LikedRecordSyncDTO;
import com.zhiwei.remark.domian.po.LikedRecord;
import com.zhiwei.remark.mapper.LikedRecordMapper;
import com.zhiwei.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.zhiwei.common.constants.MqConstants.Exchange.LIKE_RECORD_EXCHANGE;
import static com.zhiwei.common.constants.MqConstants.Key.LIKED_RECORD_SYNC_KEY;
import static com.zhiwei.common.constants.MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE;
import static com.zhiwei.remark.constants.RedisConstants.LIKES_BIZ_KEY_PREFIX;
import static com.zhiwei.remark.constants.RedisConstants.LIKES_CHANGED_TIMES_KEY_PREFIX;
import static com.zhiwei.remark.constants.RedisConstants.LIKES_SYNC_FAILED_KEY_PREFIX;
import static com.zhiwei.remark.constants.RedisConstants.LIKES_TIMES_KEY_PREFIX;

/**
 * 点赞服务 — Redis 主存储 + MQ 事件驱动行级落库
 * <p>
 * Redis 为主存储（读写均走 Redis，依赖 AOF + 从库保证数据安全），MySQL 作灾备副本：
 * <ol>
 *   <li>点赞/取消点赞通过 Lua 脚本原子操作 Redis Set（key 含 bizType）+ 点赞数 ZSet</li>
 *   <li>变更后通过 {@code RabbitMqHelper.sendAsyncWithRetry} 异步发送 MQ 持久化事件，不阻塞用户请求，
 *       消费端 {@code LikedRecordSyncListener} 行级落库，每次变更仅影响 1 行，无写放大</li>
 *   <li>持久化事件重试耗尽自动降级：写入 Redis 失败集合
 *       {@code likes:sync:failed:{bizType}:{bizId}}（member=userId），
 *       由 XXL-JOB 每日凌晨自动补偿（LikedRecordSyncCompensateTask），
 *       以 Redis 当前状态为准，精确 INSERT/DELETE，无写放大</li>
 *   <li>点赞数变更通过脏标记 + MQ 异步通知下游（如学习服务）</li>
 * </ol>
 *
 * @author author
 * @since 2026-05-13
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LikedRecordServiceRedisImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {
    private final RabbitMqHelper rabbitMqHelper;
    private final StringRedisTemplate redisTemplate;

    /** 点赞/取消点赞 + 点赞数更新的原子 Lua 脚本，从 classpath 加载，static 只初始化一次 */
    private static final DefaultRedisScript<Long> LIKE_OR_UNLIKE_SCRIPT;

    static {
        LIKE_OR_UNLIKE_SCRIPT = new DefaultRedisScript<>();
        LIKE_OR_UNLIKE_SCRIPT.setLocation(new ClassPathResource("lua/like_or_unlike.lua"));
        LIKE_OR_UNLIKE_SCRIPT.setResultType(Long.class);
    }

    /**
     * 点赞或取消点赞
     * <p>
     * 流程：
     * <ol>
     *   <li>Lua 脚本原子执行 SADD/SREM + 更新点赞数 ZSet，返回 1 表示集合发生变化</li>
     *   <li>异步发送持久化事件：RabbitMqHelper 后台线程池发送，不阻塞用户请求；
     *       重试耗尽降级写失败集合（like/unlike 分开），由 XXL-JOB 每日补偿</li>
     * </ol>
     * 幂等保证：重复点赞 SADD 返回 0、重复取消 SREM 返回 0，脚本返回 0 直接返回不做后续处理。
     *
     * @param dto 点赞请求体，包含 bizId、bizType、liked
     */
    @Override
    public void likeOrUnlike(LikeRecordFormDTO dto) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUserId();
        String bizType = dto.getBizType();
        String bizId = dto.getBizId().toString();
        String key = likesBizKey(bizType, bizId);
        String timesKey = LIKES_TIMES_KEY_PREFIX + bizType;

        // 2. Lua 原子执行：SADD/SREM 点赞集合 + ZADD 点赞数，返回 1 表示集合发生变化
        Long changed = redisTemplate.execute(
                LIKE_OR_UNLIKE_SCRIPT,
                Arrays.asList(key, timesKey, LIKES_CHANGED_TIMES_KEY_PREFIX + bizType),
                userId.toString(),
                Boolean.TRUE.equals(dto.getLiked()) ? "1" : "0",
                bizId);

        // 3. Set 未发生变化（重复点赞/取消），幂等返回
        if (changed == null || changed == 0) {
            return;
        }

        // 4. 异步发送持久化事件（重试 1 次），重试耗尽降级写失败集合，由 XXL-JOB 每日补偿
        LikedRecordSyncDTO syncDTO = LikedRecordSyncDTO.of(userId, dto.getBizId(), bizType, dto.getLiked());
        String failedKey = LIKES_SYNC_FAILED_KEY_PREFIX + bizType + ":" + bizId;

        rabbitMqHelper.sendAsyncWithRetry(LIKE_RECORD_EXCHANGE, LIKED_RECORD_SYNC_KEY, syncDTO, 1,
                () -> redisTemplate.opsForSet().add(failedKey, userId.toString()));
    }

    /**
     * 批量查询当前用户对多个业务对象是否已点赞
     * <p>
     * 使用 Redis Pipeline 将多次 SISMEMBER 合并为一次网络往返，避免 N+1 问题。
     *
     * @param bizIds  待查询的业务 ID 列表
     * @param bizType 业务类型
     * @return 当前用户已点赞的业务 ID 集合
     */
    @Override
    public Set<Long> isBizLike(List<Long> bizIds, String bizType) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUserId();
        // 2. Pipeline 批量执行 SISMEMBER，一次网络往返查询所有 bizId 的点赞状态
        List<Object> objects = redisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    StringRedisConnection src = (StringRedisConnection) connection;
                    for (Long bizId : bizIds) {
                        // SISMEMBER likes:set:{bizType}:{bizId} {userId}，返回 true/false
                        src.sIsMember(likesBizKey(bizType, bizId.toString()), userId.toString());
                    }
                    return null;
                }
        );

        // 3. 遍历 Pipeline 结果，筛选出值为 true 的 bizId 返回
        return IntStream.range(0, objects.size())
                .filter(i -> (boolean) objects.get(i))
                .mapToObj(bizIds::get)
                .collect(Collectors.toSet());
    }

    /**
     * 从脏标记集合读取点赞数变更的 bizId，批量查 ZSet 获取最新点赞数，通过 MQ 通知下游
     * <p>
     * 先读后删：MQ 发送成功才从脏标记集合移除，失败则保留等待下次重试。
     */
    public void readLikedTimesAndSendMessage(String bizType) {
        String changedKey = LIKES_CHANGED_TIMES_KEY_PREFIX + bizType;
        String timesKey = LIKES_TIMES_KEY_PREFIX + bizType;

        // 1. 读取脏标记集合（Set 天然去重：20 秒内同一 bizId 多次变化只存一条）
        Set<String> bizIds = redisTemplate.opsForSet().members(changedKey);
        if (CollUtils.isEmpty(bizIds)) return;

        List<String> bizIdList = new ArrayList<>(bizIds);

        // 2. Pipeline 批量 ZSCORE，一次网络往返获取所有 bizId 的最新点赞数
        List<Object> scores = redisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    StringRedisConnection src = (StringRedisConnection) connection;
                    for (String bizId : bizIdList) {
                        src.zScore(timesKey, bizId);
                    }
                    return null;
                }
        );

        // 3. 组装 DTO 列表（score 为 null 的跳过，如已删除的内容）
        List<LikeTimesDTO> list = new ArrayList<>();
        for (int i = 0; i < bizIdList.size(); i++) {
            Double score = (Double) scores.get(i);
            if (score != null) {
                list.add(LikeTimesDTO.of(Long.valueOf(bizIdList.get(i)), score.intValue()));
            }
        }
        if (list.isEmpty()) return;

        // 4. 批量发送 MQ 通知下游（如学习服务）更新 liked_times
        boolean success = rabbitMqHelper.send(
                LIKE_RECORD_EXCHANGE,
                StringUtils.format(LIKED_TIMES_KEY_TEMPLATE, bizType),
                list);

        // 5. 先读后删：MQ 成功才清理脏标记，失败保留等待下次重试
        if (success) {
            redisTemplate.opsForSet().remove(changedKey, bizIdList.toArray(new String[0]));
        } else {
            log.warn("点赞数 MQ 发送失败，bizType={}", bizType);
        }
    }

    /** 构建点赞用户集合的 Redis key：likes:set:{bizType}:{bizId} */
    private String likesBizKey(String bizType, String bizId) {
        return LIKES_BIZ_KEY_PREFIX + bizType + ":" + bizId;
    }

}