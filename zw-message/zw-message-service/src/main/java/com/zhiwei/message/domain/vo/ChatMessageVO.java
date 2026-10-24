package com.zhiwei.message.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 下发给前端的聊天消息帧（服务端 → 前端，JSON）。
 */
@Data
public class ChatMessageVO {

    /**
     * 消息 ID（前端 ack 与分页拉取的游标）
     */
    private Long id;

    /**
     * 发送者用户 ID
     */
    private Long senderId;

    /**
     * 发送者昵称（服务端查 user-service 补齐，前端不再逐条查用户）
     */
    private String senderName;

    /**
     * 发送者头像
     */
    private String senderIcon;

    /**
     * 消息类型：1-私聊 2-群聊
     */
    private Integer messageType;

    /**
     * 目标 ID：私聊=接收者用户 ID，群聊=群 ID
     */
    private Long targetId;

    /**
     * 消息内容（已过滤违禁词；已撤回的消息为空串）
     */
    private String content;

    /**
     * 发送时间
     */
    private LocalDateTime sentAt;

    /**
     * 是否已撤回：true 时 content 为空，前端渲染成「xxx 撤回了一条消息」占位
     */
    private Boolean recalled;
}
