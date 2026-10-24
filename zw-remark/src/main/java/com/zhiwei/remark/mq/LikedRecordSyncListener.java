package com.zhiwei.remark.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiwei.remark.domian.dto.LikedRecordSyncDTO;
import com.zhiwei.remark.domian.po.LikedRecord;
import com.zhiwei.remark.mapper.LikedRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.zhiwei.common.constants.MqConstants.Exchange.LIKE_RECORD_EXCHANGE;
import static com.zhiwei.common.constants.MqConstants.Key.LIKED_RECORD_SYNC_KEY;

/**
 * 点赞记录持久化事件监听器 —— 事件驱动行级同步的消费端（主通道）
 * <p>
 * 每次点赞/取消仅影响 1 行，无快照重拷的写放大：
 * <ol>
 *   <li>点赞 → insert ignore 插入一行，唯一索引冲突时忽略（幂等，重复消息无害）</li>
 *   <li>取消 → 按唯一键删除一行，天然幂等</li>
 * </ol>
 * <p>
 * 已知边界：同一用户极快的"点赞→取消"可能乱序到达，导致 DB 残留一行点赞记录
 * （Redis 中的真实状态和点赞数不受影响），由 XXL-JOB 每日凌晨自动补偿任务
 * （LikedRecordSyncCompensateTask）以 Redis 当前状态为准修复。
 * <p>
 * 消费异常兜底：DB 故障等异常导致消费失败时，由 MqConfig 中配置的
 * RepublishMessageRecoverer 将消息投递到 error 队列，避免无限重试。
 */
@RequiredArgsConstructor
@Slf4j
@Component
public class LikedRecordSyncListener {

    private final LikedRecordMapper recordMapper;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "liked.record.sync.queue", durable = "true"),
            exchange = @Exchange(name = LIKE_RECORD_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = LIKED_RECORD_SYNC_KEY
    ))
    public void onLikedRecordChange(LikedRecordSyncDTO dto) {
        log.debug("监听到点赞记录变更事件：{}", dto);
        if (Boolean.TRUE.equals(dto.getLiked())) {
            // 点赞：插入一行，唯一索引冲突时忽略
            recordMapper.insertIgnore(new LikedRecord()  
                    .setUserId(dto.getUserId())
                    .setBizId(dto.getBizId())
                    .setBizType(dto.getBizType()));
        } else {
            // 取消点赞：按唯一键删除一行
            recordMapper.delete(new LambdaQueryWrapper<LikedRecord>()
                    .eq(LikedRecord::getUserId, dto.getUserId())
                    .eq(LikedRecord::getBizId, dto.getBizId())
                    .eq(LikedRecord::getBizType, dto.getBizType()));
        }
    }
}