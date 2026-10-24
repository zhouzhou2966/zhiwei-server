package com.zhiwei.message.service;

import com.zhiwei.message.domain.dto.ChatAckDTO;
import com.zhiwei.message.domain.dto.ChatMessageDTO;
import com.zhiwei.message.domain.vo.ChatGroupVO;
import com.zhiwei.message.domain.vo.ChatMessageVO;
import com.zhiwei.message.domain.vo.ChatSessionVO;
import com.zhiwei.message.domain.vo.ChatUserVO;

import java.util.List;

public interface IChatService {

    /**
     * 发送消息（私聊/群聊）：DFA 过滤 → 落库 → 本机推送 → Redis 广播到其他实例。
     *
     * @param senderId 发送者，来自握手 token 解析，不信任客户端自报身份
     */
    void send(Long senderId, ChatMessageDTO dto);

    /**
     * 已读确认：把 (userId, 会话) 的已读位点前推到 messageId
     */
    void ack(Long userId, ChatAckDTO dto);

    /**
     * 撤回消息（只能撤自己发的，且在 {@link com.zhiwei.message.constants.ChatConstants#RECALL_WINDOW_MINUTES}
     * 分钟之内）。软删：置 recalled 标记并把内容从下发帧里去掉，然后推 RECALL 帧给会话参与者。
     *
     * @throws IllegalArgumentException 消息不存在 / 不是自己发的 / 超过撤回时间窗
     */
    void recall(Long userId, Long messageId);

    /**
     * 处理 Redis 广播通道收到的消息（跨实例转发入口）
     */
    void onBroadcast(String payload);

    /**
     * 处理「用户资料变更」事件：失效该用户在进程内的昵称头像缓存，
     * 并把新资料推给本机在线客户端，让已经渲染出来的头像就地更新（不必等用户刷新）。
     * <p>缓存是每个实例各自的，所以这件事必须**所有实例**都做，走 Redis pub/sub 广播。
     * <p>订阅的频道见 {@link com.zhiwei.api.constants.UserProfileConstants#PROFILE_CHANGED_CHANNEL}。
     */
    void onProfileChanged(String payload);

    /**
     * 通知所有在线用户「在线名单变了」（有人上线/下线）：
     * 本机直推 + Redis 广播给其他实例，各实例再推自己本机的连接。
     * <p>前端收到后重新拉一次在线用户列表，从而做到在线人数实时刷新。
     */
    void broadcastPresence();

    /**
     * 历史消息分页（按消息 ID 倒序向前翻，结果按时间正序返回）
     *
     * @param beforeId 0 表示从最新开始
     */
    List<ChatMessageVO> history(Long userId, Integer messageType, Long targetId, Long beforeId, int size);

    /**
     * 会话列表 + 各会话未读数（离线补拉入口：前端按会话调 history 补位点之后的消息）
     */
    List<ChatSessionVO> offlineSessions(Long userId);

    /**
     * 我的群列表
     */
    List<ChatGroupVO> myGroups(Long userId);

    /**
     * 在线用户列表（带昵称头像，排除自己）：来自 Redis 路由表，跨实例可见。
     * <p>私聊对象从「在线用户」里选：只有对方在线才可能即时收到消息，
     * 也顺带避免了「全站用户列表」这种接口的隐私与性能问题。
     */
    List<ChatUserVO> onlineUsers(Long selfId);

    /**
     * 刷新本机违禁词库并广播通知其他实例
     */
    void refreshSensitiveWords();
}
