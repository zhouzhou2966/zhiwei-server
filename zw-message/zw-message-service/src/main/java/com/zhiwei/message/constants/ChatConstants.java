package com.zhiwei.message.constants;

/**
 * 群聊模块的 Redis key 与常量。
 * <p>key 设计统一带 chat: 前缀避免与站内信等既有 key 混淆；
 * 路由表用 Hash 而不是每用户一个 String key：跨实例推送时要按
 * 「群成员批量查实例」，HMGET 一次往返，比逐用户 GET 省 n-1 次 RTT。
 */
public final class ChatConstants {

    private ChatConstants() {
    }

    /**
     * 在线路由表：Hash {userId -> instanceId}。
     * 用户 WebSocket 连上哪台实例，就把 userId 映射到那台实例的 instanceId。
     */
    public static final String ROUTE_TABLE_KEY = "chat:online:route";

    /**
     * 跨实例广播 channel：所有 message-service 实例都订阅它。
     * 收到自己发的广播后要跳过（发布者本机已直接推送过）。
     */
    public static final String BROADCAST_CHANNEL = "chat:relay";

    /**
     * Redis pub/sub 广播体里的消息类型
     */
    public static final int RELAY_CHAT = 1;
    public static final int RELAY_DFA_REFRESH = 2;
    /**
     * 在线名单变化（有用户上线/下线）：通知所有在线用户刷新在线人数/列表。
     * 与聊天消息一样要走广播，因为「上线的人」可能连在别的实例上。
     */
    public static final int RELAY_PRESENCE = 3;
    /**
     * 消息撤回：与 RELAY_CHAT 共用「VO + receiverIds」广播体，只是下发的帧类型是 RECALL
     */
    public static final int RELAY_RECALL = 4;

    /**
     * 可撤回时间窗（分钟）：超过这个时长只能看着，不能撤回
     */
    public static final int RECALL_WINDOW_MINUTES = 2;

    /**
     * 消息类型：1-私聊 2-群聊（与 chat_message.message_type 一致）
     */
    public static final int MSG_PRIVATE = 1;
    public static final int MSG_GROUP = 2;

    /**
     * 违禁词命中后的替换文本
     */
    public static final String MASK_TEXT = "***";
}
