package com.zhiwei.message.domain.dto;

import lombok.Data;

/**
 * WebSocket 上行的聊天消息帧（前端 → 服务端，JSON）。
 * <p>senderId 不由前端传入——它取自握手时 token 解析出的身份，
 * 客户端声称的身份一律不信任，防止伪造发送者。
 */
@Data
public class ChatMessageDTO {

    /**
     * 消息类型：1-私聊 2-群聊
     */
    private Integer messageType;

    /**
     * 目标 ID：私聊=对端用户 ID，群聊=群 ID
     */
    private Long targetId;

    /**
     * 原始消息内容（服务端会做 DFA 过滤后再落库/下发）
     */
    private String content;
}
