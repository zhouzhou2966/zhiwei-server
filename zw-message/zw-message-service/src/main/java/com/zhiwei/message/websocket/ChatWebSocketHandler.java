package com.zhiwei.message.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiwei.message.constants.ChatConstants;
import com.zhiwei.message.domain.dto.ChatAckDTO;
import com.zhiwei.message.domain.dto.ChatMessageDTO;
import com.zhiwei.message.service.IChatService;
import com.zhiwei.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;

/**
 * 群聊 WebSocket 处理器：连接生命周期管理 + 上行帧分发。
 *
 * <p>协议（JSON 文本帧）：
 * <pre>
 * 上行：{"type":"CHAT","messageType":2,"targetId":123,"content":"..."}
 *       {"type":"ACK","messageType":2,"targetId":123,"messageId":456}
 *       {"type":"PING"}
 * 下行：{"type":"CHAT","id":...,"senderId":...,"senderName":...,"content":...}
 *       {"type":"PONG"} / {"type":"ERROR","message":"..."}
 * </pre>
 *
 * <p>实例身份用启动时生成的 instanceId 写入 Redis 路由表。注销时先 HGET 比对
 * 再 HDEL：同一用户在两台实例各有一个连接（多端），A 实例断开时不能把
 * 指向 B 实例的路由删掉——HGET+HDEL 非原子，极端竞态下可能误删 B 的路由，
 * 后果是 B 上的用户少收一次推送、重连/补拉时自愈，可接受（严格原子用 Lua）。
 *
 * <p>⚠️ 处理上行帧时要把连接已认证的 userId 放进 {@code UserContext}（见 handleTextMessage）：
 * WS 消息不经过 MVC 拦截器链，ThreadLocal 是空的，会让链路里所有 Feign 调用缺
 * user-info 头而被下游判 401。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final LocalSessionRegistry registry;
    private final IChatService chatService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 本实例标识：同一服务多实例部署时用于区分「连接在谁身上」
     */
    public static final String INSTANCE_ID = UUID.randomUUID().toString();

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(LocalSessionRegistry.ATTR_USER_ID);
        if (userId == null) {
            // 正常到不了这里：握手拦截器不放行无身份连接；防御性关闭
            closeQuietly(session);
            return;
        }
        registry.register(userId, session);
        // 路由表登记：告诉其他实例「这个用户的连接在本实例上」。
        // 用 putIfAbsent 的返回值判断他「此前是否在线」——这是全局状态（跨实例），
        // 比「本机有没有连接」准确：多开标签页/多实例重连时后者会误判成上线。
        Boolean newlyOnline = redisTemplate.opsForHash()
                .putIfAbsent(ChatConstants.ROUTE_TABLE_KEY, String.valueOf(userId), INSTANCE_ID);
        if (Boolean.FALSE.equals(newlyOnline)) {
            // 已在线（本机或别的实例）：把路由刷新为「连接在本实例」，沿用「最后连接者优先」
            redisTemplate.opsForHash().put(ChatConstants.ROUTE_TABLE_KEY,
                    String.valueOf(userId), INSTANCE_ID);
        }
        log.info("WS 连接建立 userId={}，本实例在线用户数={}", userId, registry.localOnlineUserIds().size());
        if (Boolean.TRUE.equals(newlyOnline)) {
            // 真正从「不在线」变成「在线」：通知所有人刷新在线人数/列表
            chatService.broadcastPresence();
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session,
                                     @NonNull TextMessage message) {
        Long userId = (Long) session.getAttributes().get(LocalSessionRegistry.ATTR_USER_ID);
        if (userId == null) {
            closeQuietly(session);
            return;
        }
        // 把「这条连接已认证的身份」放进 ThreadLocal。
        // 为什么必须手动放：UserContext 平时由 HTTP 请求链路上的 UserInfoInterceptor 填充，
        // 而 WS 消息跑在 Tomcat NIO 线程上、**不经过 MVC 拦截器链**，所以它是空的。
        // 空着会导致链路里所有 Feign 调用（fillSenderInfo 查发送者昵称头像）都缺 user-info 头，
        // 被 user-service 判 401、fallback 返回 null —— 表现是「服务刚重启、缓存冷的时候
        // 第一条消息没有昵称头像」。这里放的是握手阶段已经验过的 userId，语义正确。
        // 必须在 finally 里清理：NIO 线程是复用的，残留会污染后续其他用户的请求。
        UserContext.setUserId(userId);
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            switch (type) {
                case "CHAT":
                    ChatMessageDTO dto = new ChatMessageDTO();
                    dto.setMessageType(root.path("messageType").asInt());
                    dto.setTargetId(root.path("targetId").asLong());
                    dto.setContent(root.path("content").asText(null));
                    chatService.send(userId, dto);
                    break;
                case "ACK":
                    ChatAckDTO ack = new ChatAckDTO();
                    ack.setMessageType(root.path("messageType").asInt());
                    ack.setTargetId(root.path("targetId").asLong());
                    ack.setMessageId(root.path("messageId").asLong());
                    chatService.ack(userId, ack);
                    break;
                case "RECALL":
                    chatService.recall(userId, root.path("messageId").asLong());
                    break;
                case "PING":
                    session.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
                    break;
                default:
                    sendError(session, "未知帧类型: " + type);
            }
        } catch (Exception e) {
            log.warn("WS 帧处理失败 userId={}", userId, e);
            sendError(session, "消息处理失败: " + e.getMessage());
        } finally {
            UserContext.removeUserId();
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session,
                                      @NonNull CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(LocalSessionRegistry.ATTR_USER_ID);
        if (userId == null) {
            return;
        }
        boolean noLocalSession = registry.unregister(userId, session);
        boolean wentOffline = false;
        if (noLocalSession) {
            // 该用户在本实例已无任何连接，才允许摘除路由表映射
            Object bound = redisTemplate.opsForHash().get(ChatConstants.ROUTE_TABLE_KEY,
                    String.valueOf(userId));
            if (INSTANCE_ID.equals(bound)) {
                redisTemplate.opsForHash().delete(ChatConstants.ROUTE_TABLE_KEY,
                        String.valueOf(userId));
                // 路由确实被本实例摘掉 = 他真正下线了（若路由已指向别的实例，说明是重连，仍在线）
                wentOffline = true;
            }
        }
        log.info("WS 连接关闭 userId={} status={}", userId, status);
        if (wentOffline) {
            // 真正从「在线」变成「不在线」：通知所有人刷新
            chatService.broadcastPresence();
        }
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, Throwable ex) {
        log.warn("WS 传输异常，关闭连接", ex);
        closeQuietly(session);
    }

    private void sendError(WebSocketSession session, String msg) {
        try {
            session.sendMessage(new TextMessage(
                    "{\"type\":\"ERROR\",\"message\":\"" + msg.replace("\"", "'") + "\"}"));
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
        }
    }
}
