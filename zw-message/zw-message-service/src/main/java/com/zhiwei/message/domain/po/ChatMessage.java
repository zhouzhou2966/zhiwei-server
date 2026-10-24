package com.zhiwei.message.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天消息表，私聊/群聊共用一张表。
 * <p>设计要点：
 * <ul>
 *   <li>主键用雪花 ID（ASSIGN_ID）而非自增：消息 ID 要作为跨实例推送的排序依据和
 *       前端 ack 位点，分布式部署下自增会产生冲突；雪花 ID 整体趋势递增，可直接比较。</li>
 *   <li>没有 status 字段：消息一旦落库即视为已投递，在线推 WebSocket、离线走
 *       「上线后按 ack 位点补拉」，不需要在消息行上记每个接收者的状态——那是
 *       消息回执表（写放大 n 倍）的做法，群聊场景代价太高。撤回用的是消息级的
 *       recalled 布尔位，与「每个接收者的状态」不是一回事。</li>
 *   <li>sent_at 精确到毫秒：同一秒内多条消息在前端的展示顺序要稳定。</li>
 * </ul>
 */
@Data
@TableName("chat_message")
public class ChatMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 消息 ID，雪花算法生成
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 发送者用户 ID
     */
    private Long senderId;

    /**
     * 消息内容（已经过 DFA 违禁词过滤后的最终文本）
     */
    private String content;

    /**
     * 消息类型：1-私聊 2-群聊
     */
    private Integer messageType;

    /**
     * 目标 ID：私聊时为接收者用户 ID，群聊时为群 ID
     */
    private Long targetId;

    /**
     * 发送时间，精确到毫秒
     */
    private LocalDateTime sentAt;

    /**
     * 是否已撤回（软删）。
     * <p>为什么软删而不是 delete 行：消息 ID 同时是 ack 位点和历史分页游标，
     * 删行会让「补拉位点之后的消息」这条链路对不上；且撤回要留痕（显示占位）。
     * 撤回后 content 不再下发（见 toVO），原文只留在库里。
     */
    private Boolean recalled;
}
