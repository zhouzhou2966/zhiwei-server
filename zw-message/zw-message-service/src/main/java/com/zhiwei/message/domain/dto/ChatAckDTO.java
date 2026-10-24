package com.zhiwei.message.domain.dto;

import lombok.Data;

/**
 * WebSocket 上行的已读确认帧（前端 → 服务端）。
 */
@Data
public class ChatAckDTO {

    /**
     * 消息类型：1-私聊 2-群聊
     */
    private Integer messageType;

    /**
     * 会话目标：群 ID 或私聊对端用户 ID
     */
    private Long targetId;

    /**
     * 已读到的最后一条消息 ID
     */
    private Long messageId;
}
