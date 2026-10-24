package com.zhiwei.message.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiwei.message.domain.vo.ChatMessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 本机 WebSocket 会话注册表：userId → 该用户在本实例上的所有连接。
 * <p>为什么不复用一个 Map 解决：它同时承担两个职责——
 * <ul>
 *   <li>连接管理：上下线时增删，供「路由表注销」判断该用户是否还有本地连接；</li>
 *   <li>本机推送：跨实例 relay 收到广播后，只对「路由表指到本实例」的用户推送，
 *       最终都落到这个 Map 上。</li>
 * </ul>
 * <p>为什么值是 Set 而不是单个 session：同一用户可能开多个标签页（多端），
 * 一条消息要推给他的所有连接；任一连接写失败只关闭那一条，不影响其他。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalSessionRegistry {

    public static final String ATTR_USER_ID = "chatUserId";

    private final ObjectMapper objectMapper;

    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    /**
     * 注销一条连接。
     *
     * @return 该用户在本实例上是否已没有任何连接（true = 应从 Redis 路由表摘除）
     */
    public boolean unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(userId);
        if (set == null) {
            return true;
        }
        set.remove(session);
        if (set.isEmpty()) {
            sessions.remove(userId, set);
            return true;
        }
        return false;
    }

    public boolean isOnlineLocally(Long userId) {
        Set<WebSocketSession> set = sessions.get(userId);
        return set != null && !set.isEmpty();
    }

    public Set<Long> localOnlineUserIds() {
        return sessions.keySet();
    }

    /**
     * 按指定帧类型推送一条消息体。
     * <p>撤回（RECALL）复用 CHAT 的 VO 结构、只换 type：前端据此走「把该条消息标记为
     * 已撤回」而不是「追加一条新消息」。
     * <p>写失败的 session 直接关闭——WebSocket 写失败通常意味着对端已断，
     * 留着只会让 onClose 延迟触发、路由表状态僵死。
     */
    public void pushFrame(Long userId, String frameType, ChatMessageVO vo) {
        String payload = toJson(frameType, vo);
        if (payload == null) {
            return;
        }
        sendTo(sessions.get(userId), userId, payload);
    }

    /**
     * 向一批用户推送同一条消息帧（帧内容与接收者无关时用）。
     * <p>只序列化一次：群聊消息对全体成员内容完全相同，若逐个 pushFrame，
     * JSON 序列化次数会被放大成「本机在线人数」倍——convertValue + writeValueAsString 都不便宜。
     */
    public void pushBatch(Collection<Long> userIds, String frameType, ChatMessageVO vo) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        String payload = toJson(frameType, vo);
        if (payload == null) {
            return;
        }
        for (Long userId : userIds) {
            sendTo(sessions.get(userId), userId, payload);
        }
    }

    /**
     * 向本机所有在线连接推送一条已序列化好的原始帧。
     * <p>用于 presence 这类「广播给所有在线用户」的帧（payload 不含收件人差异，
     * 可以只序列化一次；而 CHAT 帧要按接收者视角改写 targetId，所以仍走 push(userId, vo)）。
     */
    public void pushToAll(String payload) {
        for (Map.Entry<Long, Set<WebSocketSession>> entry : sessions.entrySet()) {
            sendTo(entry.getValue(), entry.getKey(), payload);
        }
    }

    private void sendTo(Set<WebSocketSession> set, Long userId, String payload) {
        if (set == null || set.isEmpty()) {
            return;
        }
        for (WebSocketSession session : set) {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException e) {
                log.warn("推送失败，关闭 session，userId={}", userId, e);
                try {
                    session.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 序列化下行帧。
     * <p>必须带上 type 字段：前端按帧类型分发（CHAT/RECALL/PONG/ERROR），
     * 裸发 ChatMessageVO 会让前端拿不到类型、消息被静默丢弃。
     */
    private String toJson(String frameType, ChatMessageVO vo) {
        try {
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("type", frameType);
            frame.setAll(objectMapper.convertValue(vo, ObjectNode.class));
            return objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            // 序列化不可序列化对象才可能发生，理论上到不了这里；兜底返回 null 由调用方过滤
            log.error("ChatMessageVO 序列化失败", e);
            return null;
        }
    }
}
