package com.zhiwei.message.domain.vo;

import lombok.Data;

/**
 * 会话（群聊或私聊）的未读概要，用于离线补拉入口。
 */
@Data
public class ChatSessionVO {

    /**
     * 消息类型：1-私聊 2-群聊
     */
    private Integer messageType;

    /**
     * 会话目标：群 ID 或私聊对端用户 ID
     */
    private Long targetId;

    /**
     * 会话名称：群名 / 对端用户昵称
     */
    private String targetName;

    /**
     * 会话头像：私聊=对端用户头像 URL；群聊暂为空（群表没有头像字段，前端用「#」兜底）
     */
    private String targetIcon;

    /**
     * 未读消息数（位点之后的消息条数）
     */
    private Long unreadCount;

    /**
     * 已读位点（最后已读的消息 ID）
     */
    private Long lastAckedId;

    /**
     * 该会话最近一条消息 ID（前端会话列表按它倒序 = 最近活跃的排最前）
     */
    private Long lastMessageId;

    /**
     * 群成员数（仅群聊会话有值，前端顶部展示「共 N 人」）
     */
    private Long memberCount;
}
