package com.zhiwei.message.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户已读位点：每个用户在每个会话（群 / 私聊对端）上「已读到的最大消息 ID」。
 * <p>离线消息方案选型：不用「为每个接收者插一条回执」的写放大方案（群聊一条消息
 * 要写 n 行），而是每个 (user, target) 只维护一个游标——
 * <ul>
 *   <li>ack 时取 max 上推（游标只会前进，重复 ack / 乱序 ack 无害）；</li>
 *   <li>离线补拉 = 查 target_id > last_acked_id 的消息，天然幂等；</li>
 *   <li>未读数 = 按位点 count，不存计数就不会有计数漂移。</li>
 * </ul>
 */
@Data
@TableName("chat_user_ack")
public class ChatUserAck implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键，雪花算法生成
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 消息类型：1-私聊 2-群聊
     */
    private Integer messageType;

    /**
     * 会话目标：群 ID 或私聊对端用户 ID
     */
    private Long targetId;

    /**
     * 已读到的最大消息 ID
     */
    private Long lastAckedId;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
