package com.zhiwei.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zhiwei.api.client.user.UserClient;
import com.zhiwei.api.dto.user.UserDTO;
import com.zhiwei.message.constants.ChatConstants;
import com.zhiwei.message.domain.dto.ChatAckDTO;
import com.zhiwei.message.domain.dto.ChatMessageDTO;
import com.zhiwei.message.domain.po.ChatGroup;
import com.zhiwei.message.domain.po.ChatGroupMember;
import com.zhiwei.message.domain.po.ChatMessage;
import com.zhiwei.message.domain.po.ChatUserAck;
import com.zhiwei.message.domain.vo.ChatGroupVO;
import com.zhiwei.message.domain.vo.ChatMessageVO;
import com.zhiwei.message.domain.vo.ChatSessionVO;
import com.zhiwei.message.domain.vo.ChatUserVO;
import com.zhiwei.message.mapper.ChatGroupMapper;
import com.zhiwei.message.mapper.ChatGroupMemberMapper;
import com.zhiwei.message.mapper.ChatMessageMapper;
import com.zhiwei.message.mapper.ChatUserAckMapper;
import com.zhiwei.message.service.IChatService;
import com.zhiwei.message.websocket.ChatWebSocketHandler;
import com.zhiwei.message.websocket.DfaWordFilter;
import com.zhiwei.message.websocket.LocalSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.zhiwei.message.websocket.ChatWebSocketHandler.INSTANCE_ID;

/**
 * 群聊/私聊核心链路：
 * <pre>
 * 发送方实例                                接收方实例
 * ─────────────────                        ─────────────────
 * WS 帧 → DFA 过滤 → 落库
 *       → 本机在线接收者直推
 *       → Redis PUBLISH chat:relay ──→ onBroadcast：跳过发布者自身，
 *                                       HMGET 路由表筛出「连接在本实例」的
 *                                       接收者，逐个直推
 * </pre>
 * 在线推送是尽力而为：推送失败的接收者下次上线/打开会话时，
 * 按 chat_user_ack 位点补拉 >lastAckedId 的消息，一致性由落库保证。
 *
 * <p>另一条链路是「用户资料（昵称/头像）变更」：
 * <pre>
 * zw-user 落库成功 → Redis PUBLISH user:profile:changed（带 userId + name/icon 快照）
 *                    ──→ 每个 message-service 实例
 *                        → 失效本机 userCache
 *                        → 把快照包成 PROFILE_CHANGED 帧推给本机所有在线连接
 * </pre>
 * 它走的是**另一条频道**而不是 chat:relay：资料变更是 user↔message 的跨服务契约，
 * 且「所有实例都该收到、各自处理自己的本机连接」，pub/sub 的广播语义正好合适。
 * 事件里带快照而不是让订阅方回查，是因为监听线程没有用户上下文、Feign 会 401（见方法注释）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {

    private final ChatMessageMapper messageMapper;
    private final ChatGroupMapper groupMapper;
    private final ChatGroupMemberMapper groupMemberMapper;
    private final ChatUserAckMapper ackMapper;
    private final UserClient userClient;
    private final DfaWordFilter dfaWordFilter;
    private final LocalSessionRegistry registry;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发送者昵称/头像缓存：每条消息推送都要带昵称头像，直查 user-service 会把它
     * 打成热点（online-mooc 版本就是每条消息同步查一次，没有缓存）。
     * Caffeine 进程内缓存 + 5 分钟过期，昵称改名最多延迟 5 分钟可见，可接受。
     */
    private final Cache<Long, UserDTO> userCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();

    /**
     * 单条消息内容上限：聊天消息超长通常是刷屏/粘贴攻击，直接拒绝
     */
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int MAX_HISTORY_SIZE = 100;

    /**
     * presence 帧（服务端 → 前端）：内容对所有接收者都一样，序列化一次复用
     */
    private static final String PRESENCE_FRAME = "{\"type\":\"PRESENCE\"}";

    /**
     * 用户资料变更帧（服务端 → 前端）：把「某人换了昵称/头像」推给在线客户端，
     * 让已经渲染出来的头像就地更新，而不是等用户自己刷新页面。
     */
    private static final String PROFILE_CHANGED_FRAME = "PROFILE_CHANGED";

    // ==================== 发送 ====================

    @Override
    public void send(Long senderId, ChatMessageDTO dto) {
        // 1.参数校验
        if (dto.getTargetId() == null || dto.getTargetId() <= 0) {
            throw new IllegalArgumentException("消息目标不合法");
        }
        String rawContent = dto.getContent();
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        if (rawContent.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("消息内容过长，最多 " + MAX_CONTENT_LENGTH + " 字");
        }
        boolean isGroup = dto.getMessageType() != null && dto.getMessageType() == ChatConstants.MSG_GROUP;
        if (isGroup && !isGroupMember(dto.getTargetId(), senderId)) {
            // 非群成员不能向群发消息：WS 是长连接，发消息时不能只信「连接时的状态」
            throw new IllegalArgumentException("不在该群中，无权发送");
        }

        // 2.DFA 过滤：命中替换 *** 后照常发送（拒收会导致前端收不到任何反馈，
        //   替换策略让「谁说了违禁词、被替换成了什么」对发送者自己可见）
        String filtered = dfaWordFilter.filter(rawContent);

        // 3.落库：消息 ID 用雪花算法，它是跨实例推送排序与 ack 位点的统一游标
        ChatMessage message = new ChatMessage();
        message.setSenderId(senderId);
        message.setContent(filtered);
        message.setMessageType(isGroup ? ChatConstants.MSG_GROUP : ChatConstants.MSG_PRIVATE);
        message.setTargetId(dto.getTargetId());
        message.setSentAt(LocalDateTime.now());
        messageMapper.insert(message);

        // 4.组装下发帧并补发送者资料
        ChatMessageVO vo = toVO(message);
        fillSenderInfo(vo);
        List<Long> receiverIds = conversationMembers(dto.getTargetId(), isGroup, senderId);

        // 5.本机在线接收者直推（群聊只序列化一次，见 pushToLocal）
        pushToLocal("CHAT", vo, receiverIds);

        // 6.Redis 广播给其他实例：带 receiverIds，各实例用路由表判断接收者是否在本机
        publishRelay(ChatConstants.RELAY_CHAT, vo, receiverIds);
    }

    /**
     * 把一条消息帧推给一批本机接收者。
     * <p>为什么分两种情况：群聊帧对所有接收者**完全相同**，序列化一次即可（否则序列化次数
     * 被放大成「本机在线人数」倍）；而私聊帧的 targetId 要按接收者视角改写（见 frameFor），
     * 但私聊两端最多两个接收者，逐个序列化的代价可以忽略。
     */
    private void pushToLocal(String frameType, ChatMessageVO vo, Collection<Long> receiverIds) {
        if (vo.getMessageType() != null && vo.getMessageType() == ChatConstants.MSG_GROUP) {
            registry.pushBatch(receiverIds, frameType, vo);
            return;
        }
        for (Long receiverId : receiverIds) {
            registry.pushFrame(receiverId, frameType, frameFor(vo, receiverId));
        }
    }

    /**
     * 会话参与者 = 这条消息要推给谁。
     * <ul>
     *   <li>群聊：全体群成员（天然含发送者本人——前端不做本地回显，靠这条回推显示自己的消息）；</li>
     *   <li>私聊：对端 + 发送者本人。必须显式带上发送者：前端同样不做本地回显，
     *       漏掉发送者就会出现「我发的消息只有对方能看到、自己这边不显示」。</li>
     * </ul>
     */
    private List<Long> conversationMembers(Long targetId, boolean isGroup, Long senderId) {
        if (isGroup) {
            return groupMemberMapper.selectList(new QueryWrapper<ChatGroupMember>()
                            .lambda().eq(ChatGroupMember::getGroupId, targetId))
                    .stream().map(ChatGroupMember::getUserId).distinct().collect(Collectors.toList());
        }
        return senderId.equals(targetId)
                ? Collections.singletonList(senderId)
                : Arrays.asList(senderId, targetId);
    }

    /**
     * 按「接收者视角」改写私聊帧的 targetId。
     * <p>私聊会话是对称的，但库里一行消息只有一个 target_id（接收者）。若原样下发：
     * 发送者收到时 targetId=对端（正确），接收者收到时 targetId=自己（错误）——
     * 前端用 targetId 当会话 key，接收方会算出一个「对端是自己」的假会话，
     * 真正的会话反而收不到未读。所以统一约定：targetId = 该会话的对端（对方）。
     */
    private ChatMessageVO frameFor(ChatMessageVO vo, Long receiverId) {
        if (vo.getMessageType() == null || vo.getMessageType() != ChatConstants.MSG_PRIVATE) {
            return vo;   // 群聊 targetId 是群 ID，对所有接收者都一样，无需改写
        }
        Long peerId = vo.getSenderId().equals(receiverId) ? vo.getTargetId() : vo.getSenderId();
        if (peerId.equals(vo.getTargetId())) {
            return vo;   // 发送者本人：targetId 本来就是对端
        }
        ChatMessageVO copy = new ChatMessageVO();
        copy.setId(vo.getId());
        copy.setSenderId(vo.getSenderId());
        copy.setSenderName(vo.getSenderName());
        copy.setSenderIcon(vo.getSenderIcon());
        copy.setMessageType(vo.getMessageType());
        copy.setTargetId(peerId);
        copy.setContent(vo.getContent());
        copy.setSentAt(vo.getSentAt());
        return copy;
    }

    private void publishRelay(int relayType, ChatMessageVO vo, List<Long> receiverIds) {
        try {
            Map<String, Object> relay = new HashMap<>(4);
            relay.put("relayType", relayType);
            relay.put("publisher", INSTANCE_ID);
            relay.put("message", vo);
            relay.put("receiverIds", receiverIds);
            redisTemplate.convertAndSend(ChatConstants.BROADCAST_CHANNEL,
                    objectMapper.writeValueAsString(relay));
        } catch (Exception e) {
            // 广播失败不回滚落库：在线推送尽力而为，离线由位点补拉兜底
            log.error("跨实例广播失败 messageId={}", vo.getId(), e);
        }
    }

    // ==================== 跨实例接收 ====================

    @Override
    public void onBroadcast(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("广播体解析失败: {}", payload, e);
            return;
        }
        int relayType = root.path("relayType").asInt();
        if (relayType == ChatConstants.RELAY_DFA_REFRESH) {
            dfaWordFilter.reload();
            return;
        }
        if (relayType == ChatConstants.RELAY_PRESENCE) {
            // 在线名单变了：推给本机所有在线用户（自己发的广播跳过，本机在 broadcastPresence 已推过）
            if (!INSTANCE_ID.equals(root.path("publisher").asText())) {
                registry.pushToAll(PRESENCE_FRAME);
            }
            return;
        }
        // 撤回与聊天消息共用「VO + receiverIds」的广播体，区别只在下发的帧类型
        String frameType;
        if (relayType == ChatConstants.RELAY_CHAT) {
            frameType = "CHAT";
        } else if (relayType == ChatConstants.RELAY_RECALL) {
            frameType = "RECALL";
        } else {
            return;
        }
        if (INSTANCE_ID.equals(root.path("publisher").asText())) {
            // 自己发的广播：本机在 send/recall 时已直推过，跳过防止重复
            return;
        }
        JsonNode messageNode = root.path("message");
        ChatMessageVO vo;
        try {
            vo = objectMapper.treeToValue(messageNode, ChatMessageVO.class);
        } catch (Exception e) {
            log.warn("广播消息帧解析失败", e);
            return;
        }
        // 一次 HMGET 批量拿所有接收者的路由，只有路由指到本实例的才推
        List<String> receiverIds = new ArrayList<>();
        root.path("receiverIds").forEach(n -> receiverIds.add(n.asText()));
        List<Object> routes = redisTemplate.opsForHash()
                .multiGet(ChatConstants.ROUTE_TABLE_KEY, new ArrayList<>(receiverIds));
        List<Long> localReceiverIds = new ArrayList<>();
        for (int i = 0; i < receiverIds.size(); i++) {
            if (INSTANCE_ID.equals(routes.get(i))) {
                localReceiverIds.add(Long.valueOf(receiverIds.get(i)));
            }
        }
        pushToLocal(frameType, vo, localReceiverIds);
    }

    // ==================== 用户资料变更（缓存失效） ====================

    /**
     * 收到「某用户改了昵称/头像」的事件，失效本机缓存并把新资料推给在线客户端。
     *
     * <p>为什么必须有这一步：{@link #userCache} 是**进程内**缓存（Caffeine，TTL 5 分钟），
     * 用户换完头像回聊天页看到的还是旧头像，而且刷新浏览器也没用 —— 缓存不在浏览器里。
     * 靠 TTL 自然过期要等 5 分钟，体验上等同于「没生效」，所以由 user-service 落库后广播一次。
     *
     * <p>做两件事：
     * <ol>
     *   <li><b>失效缓存</b>（主路径，不能省）：删掉之后下次组装消息自然回查 user-service。
     *       删一个不存在的 key 是空操作，重复投递/多实例重复处理都无害（幂等）。</li>
     *   <li><b>把事件里带的快照推给本机在线连接</b>（体验增强）：只失效缓存的话，客户端要等
     *       **自己下一次发请求**（刷新/切会话）才看得到新头像；主动推一帧，
     *       屏幕上已经渲染出来的头像就能就地替换，做到真正的零延迟。</li>
     * </ol>
     *
     * <p><b>为什么这里不能像 {@link #fillSenderInfo} 那样回查 user-service</b>：
     * 本方法跑在 Redis 监听线程上，那里没有用户上下文（{@code UserContext} 是 ThreadLocal，
     * 由 HTTP 请求链路上的 UserInfoInterceptor 填充），Feign 的
     * {@code FeignRelayUserInterceptor} 因此带不上 user-info 头，下游直接判 401、
     * fallback 返回 null —— 实测踩过这个坑，所以改为由发布方（zw-user，在请求线程里、
     * 手里就有最新值）把 name/icon 快照一并带过来。顺带也避免了在监听线程里做阻塞 I/O
     * （那会拖住所有频道的事件处理）。
     *
     * <p>为什么不需要跨实例转发（不像聊天消息那样走 chat:relay）：
     * 「资料变更」事件本身是发在 {@code user:profile:changed} 上的 pub/sub，
     * **每个 message-service 实例都会收到一份**，各自推自己本机的连接即可，
     * 天然覆盖所有实例，也不会重复推送。
     *
     * <p>已知取舍：Redis 不可用期间事件会丢，退化成「等 TTL 过期 + 客户端自己刷新」，
     * 不会造成数据错误，只是可见性变差。
     */
    @Override
    public void onProfileChanged(String payload) {
        Long userId;
        String name;
        String icon;
        try {
            JsonNode root = objectMapper.readTree(payload);
            userId = root.path("userId").asLong();
            // 允许缺失/null：发布方本次没改该字段（例如只换头像时前端不传昵称）
            name = root.path("name").isTextual() ? root.path("name").asText() : null;
            icon = root.path("icon").isTextual() ? root.path("icon").asText() : null;
        } catch (Exception e) {
            log.warn("用户资料变更事件解析失败: {}", payload, e);
            return;
        }
        if (userId == null || userId <= 0) {
            return;
        }
        // 1.先失效缓存（保证一致性）
        userCache.invalidate(userId);
        // 2.再把快照推给本机在线连接（昵称和头像都没变就不用推了，省一次无意义的广播）
        if (name == null && icon == null) {
            return;
        }
        String frame = buildProfileChangedFrame(userId, name, icon);
        if (frame != null) {
            registry.pushToAll(frame);
        }
    }

    /**
     * 组装「用户资料变更」下行帧。
     * <p>带上新的昵称与头像，而不是只带 userId：前端拿到就能就地替换，
     * 不必为了一个新头像再发一轮请求（会被放大成「在线客户端数 × 1 次」）。
     * <p>值为 null 的字段照样下发（序列化成 {@code "name":null}），
     * 前端按「字段为空就跳过」处理 —— 这样协议对「只改了头像」这种部分更新也是完备的。
     */
    private String buildProfileChangedFrame(Long userId, String name, String icon) {
        try {
            Map<String, Object> frame = new LinkedHashMap<>(4);
            frame.put("type", PROFILE_CHANGED_FRAME);
            frame.put("userId", userId);
            frame.put("name", name);
            frame.put("icon", icon);
            return objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            log.warn("用户资料变更帧序列化失败 userId={}", userId, e);
            return null;
        }
    }

    // ==================== 在线名单变化广播 ====================

    @Override
    public void broadcastPresence() {
        // 本机所有在线用户直推一次，再广播给其他实例（各实例推自己本机的连接）
        registry.pushToAll(PRESENCE_FRAME);
        try {
            Map<String, Object> relay = new HashMap<>(2);
            relay.put("relayType", ChatConstants.RELAY_PRESENCE);
            relay.put("publisher", INSTANCE_ID);
            redisTemplate.convertAndSend(ChatConstants.BROADCAST_CHANNEL,
                    objectMapper.writeValueAsString(relay));
        } catch (Exception e) {
            // 广播失败不影响本机：本机已推送，其他实例的在线人数最迟在下次上线事件时纠正
            log.error("presence 广播失败（本机已推送）", e);
        }
    }

    // ==================== ack ====================

    @Override
    public void ack(Long userId, ChatAckDTO dto) {
        if (dto.getTargetId() == null || dto.getMessageId() == null) {
            return;
        }
        ChatUserAck ack = new ChatUserAck();
        ack.setId(IdWorker.getId());
        ack.setUserId(userId);
        ack.setMessageType(dto.getMessageType());
        ack.setTargetId(dto.getTargetId());
        ack.setLastAckedId(dto.getMessageId());
        // 库内原子 upsert 取 max：多端并发 ack 时位点只前进不后退
        ackMapper.upsertAck(ack);
    }

    // ==================== 撤回 ====================

    @Override
    public void recall(Long userId, Long messageId) {
        if (messageId == null) {
            throw new IllegalArgumentException("消息 ID 不能为空");
        }
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new IllegalArgumentException("消息不存在");
        }
        // 只能撤自己发的：不校验的话任何人都能撤别人的消息
        if (!message.getSenderId().equals(userId)) {
            throw new IllegalArgumentException("只能撤回自己发送的消息");
        }
        if (Boolean.TRUE.equals(message.getRecalled())) {
            return;   // 幂等：多端同时点、或重复点击，直接当成功
        }
        if (message.getSentAt().plusMinutes(ChatConstants.RECALL_WINDOW_MINUTES)
                .isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException(
                    "超过 " + ChatConstants.RECALL_WINDOW_MINUTES + " 分钟的消息不能撤回");
        }
        // 带 recalled=0 条件的更新：两端并发撤回时只有一个能改到，避免重复广播
        int updated = messageMapper.update(null, new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getId, messageId)
                .eq(ChatMessage::getRecalled, false)
                .set(ChatMessage::getRecalled, true));
        if (updated == 0) {
            return;
        }
        // 下发 RECALL 帧：VO 里 content 已被 toVO 置空（撤回后原文不再出服务端）
        message.setRecalled(true);
        ChatMessageVO vo = toVO(message);
        fillSenderInfo(vo);
        boolean isGroup = message.getMessageType() != null
                && message.getMessageType() == ChatConstants.MSG_GROUP;
        List<Long> receiverIds = conversationMembers(message.getTargetId(), isGroup, message.getSenderId());
        pushToLocal("RECALL", vo, receiverIds);
        publishRelay(ChatConstants.RELAY_RECALL, vo, receiverIds);
    }

    // ==================== 拉取 ====================

    @Override
    public List<ChatMessageVO> history(Long userId, Integer messageType, Long targetId,
                                       Long beforeId, int size) {
        boolean isGroup = messageType != null && messageType == ChatConstants.MSG_GROUP;
        if (isGroup && !isGroupMember(targetId, userId)) {
            throw new IllegalArgumentException("不在该群中，无权查看");
        }
        int limit = Math.min(Math.max(size, 1), MAX_HISTORY_SIZE);
        LambdaQueryWrapper<ChatMessage> wrapper = new QueryWrapper<ChatMessage>().lambda()
                .eq(ChatMessage::getMessageType, messageType);
        if (isGroup) {
            // 群聊：target_id 就是群 ID，取该群全部消息
            wrapper.eq(ChatMessage::getTargetId, targetId);
        } else {
            // 私聊：一次会话有两个方向，只按 target_id=对端 过滤会漏掉「对端发给我」的消息
            // （那种消息的 target_id 是我自己）。必须按 (我→对端) OR (对端→我) 对称取，
            // 否则自己只能看到自己发出去的一半，对方的话永远不出现。
            wrapper.and(w -> w
                    .nested(x -> x.eq(ChatMessage::getSenderId, userId)
                            .eq(ChatMessage::getTargetId, targetId))
                    .or(y -> y.eq(ChatMessage::getSenderId, targetId)
                            .eq(ChatMessage::getTargetId, userId)));
        }
        List<ChatMessage> records = messageMapper.selectList(wrapper
                .lt(beforeId != null && beforeId > 0, ChatMessage::getId, beforeId)
                .orderByDesc(ChatMessage::getId)
                .last("limit " + limit));
        // DB 按游标倒序取最新的 N 条，返回给前端时转回时间正序
        Collections.reverse(records);
        List<ChatMessageVO> result = records.stream().map(this::toVO).collect(Collectors.toList());
        fillSenderInfo(result);
        return result;
    }

    @Override
    public List<ChatSessionVO> offlineSessions(Long userId) {
        // 1.我的群列表
        List<ChatGroupMember> memberships = groupMemberMapper.selectList(
                new QueryWrapper<ChatGroupMember>().lambda().eq(ChatGroupMember::getUserId, userId));
        Map<Long, String> groupNames = memberships.isEmpty() ? Collections.emptyMap()
                : groupMapper.selectBatchIds(
                        memberships.stream().map(ChatGroupMember::getGroupId).collect(Collectors.toList()))
                        .stream().collect(Collectors.toMap(ChatGroup::getId, ChatGroup::getName));

        // 1.1 各群最近一条消息 ID：会话列表要按「最近有消息」排序，一次分组查询批量取
        List<Long> groupIds = memberships.stream().map(ChatGroupMember::getGroupId)
                .distinct().collect(Collectors.toList());
        Map<Long, Long> groupLastId = groupIds.isEmpty() ? Collections.emptyMap()
                : messageMapper.selectMaps(new QueryWrapper<ChatMessage>()
                        .select("target_id", "MAX(id) AS max_id")
                        .lambda()
                        .eq(ChatMessage::getMessageType, ChatConstants.MSG_GROUP)
                        .in(ChatMessage::getTargetId, groupIds)
                        .groupBy(ChatMessage::getTargetId))
                .stream().collect(Collectors.toMap(
                        r -> ((Number) r.get("target_id")).longValue(),
                        r -> ((Number) r.get("max_id")).longValue()));

        // 2.与我有过私聊往来的对端：我发的 + 发给我的都要算。
        //   只看 target_id=我 会漏掉「我发起、对方还没回」的会话，刷新后该会话就丢了。
        List<ChatMessage> myPrivate = messageMapper.selectList(new QueryWrapper<ChatMessage>()
                .lambda()
                .eq(ChatMessage::getMessageType, ChatConstants.MSG_PRIVATE)
                .and(w -> w.eq(ChatMessage::getSenderId, userId)
                        .or().eq(ChatMessage::getTargetId, userId))
                .orderByDesc(ChatMessage::getId));
        // 对端 id → 该会话最近一条消息 id（LinkedHashMap 保序：最近活跃的排在前面）
        Map<Long, Long> peerLastId = new LinkedHashMap<>();
        for (ChatMessage m : myPrivate) {
            Long peerId = m.getSenderId().equals(userId) ? m.getTargetId() : m.getSenderId();
            peerLastId.putIfAbsent(peerId, m.getId());
        }

        // 3.我的全部已读位点：key = messageType + ":" + targetId
        Map<String, Long> ackMap = ackMapper.selectList(
                        new QueryWrapper<ChatUserAck>().lambda().eq(ChatUserAck::getUserId, userId))
                .stream()
                .collect(Collectors.toMap(a -> a.getMessageType() + ":" + a.getTargetId(),
                        ChatUserAck::getLastAckedId, Math::max));

        // 4.逐会话计算未读数。会话数与用户量正相关，逐会话 count 在演示规模下够用；
        //   更大规模应改为「位点行上冗余未读数计数器 + 收信时 INCR」把查询变 O(1)
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatGroupMember m : memberships) {
            ChatSessionVO vo = new ChatSessionVO();
            vo.setMessageType(ChatConstants.MSG_GROUP);
            vo.setTargetId(m.getGroupId());
            vo.setTargetName(groupNames.getOrDefault(m.getGroupId(), "群聊" + m.getGroupId()));
            long lastAcked = ackMap.getOrDefault(ChatConstants.MSG_GROUP + ":" + m.getGroupId(), 0L);
            vo.setLastAckedId(lastAcked);
            vo.setLastMessageId(groupLastId.getOrDefault(m.getGroupId(), 0L));
            vo.setMemberCount(groupMemberMapper.selectCount(new QueryWrapper<ChatGroupMember>()
                    .lambda().eq(ChatGroupMember::getGroupId, m.getGroupId())));
            vo.setUnreadCount(messageMapper.selectCount(new QueryWrapper<ChatMessage>()
                    .lambda()
                    .eq(ChatMessage::getMessageType, ChatConstants.MSG_GROUP)
                    .eq(ChatMessage::getTargetId, m.getGroupId())
                    // 已撤回的不算未读：否则对方发完立刻撤回，这边还挂着一个点不掉的红点
                    .eq(ChatMessage::getRecalled, false)
                    .gt(ChatMessage::getId, lastAcked)));
            result.add(vo);
        }
        for (Map.Entry<Long, Long> entry : peerLastId.entrySet()) {
            Long peerId = entry.getKey();
            ChatSessionVO vo = new ChatSessionVO();
            vo.setMessageType(ChatConstants.MSG_PRIVATE);
            vo.setTargetId(peerId);
            long lastAcked = ackMap.getOrDefault(ChatConstants.MSG_PRIVATE + ":" + peerId, 0L);
            vo.setLastAckedId(lastAcked);
            vo.setLastMessageId(entry.getValue());
            vo.setUnreadCount(messageMapper.selectCount(new QueryWrapper<ChatMessage>()
                    .lambda()
                    .eq(ChatMessage::getMessageType, ChatConstants.MSG_PRIVATE)
                    .eq(ChatMessage::getSenderId, peerId)
                    .eq(ChatMessage::getTargetId, userId)
                    .eq(ChatMessage::getRecalled, false)
                    .gt(ChatMessage::getId, lastAcked)));
            result.add(vo);
        }
        // 对端昵称/头像统一补齐
        Set<Long> peerIds = result.stream()
                .filter(v -> v.getMessageType() == ChatConstants.MSG_PRIVATE)
                .map(ChatSessionVO::getTargetId).collect(Collectors.toSet());
        if (!peerIds.isEmpty()) {
            Map<Long, UserDTO> users = batchUsers(peerIds);
            result.forEach(v -> {
                if (v.getMessageType() == ChatConstants.MSG_PRIVATE) {
                    UserDTO u = users.get(v.getTargetId());
                    v.setTargetName(u == null ? "用户" + v.getTargetId() : u.getName());
                    v.setTargetIcon(u == null ? null : u.getIcon());
                }
            });
        }
        // 按「最近一条消息」倒序：最近有动静的会话排最前（原来按已读位点排序语义不对）
        result.sort(Comparator.comparingLong(
                (ChatSessionVO v) -> v.getLastMessageId() == null ? 0L : v.getLastMessageId()).reversed());
        return result;
    }

    @Override
    public List<ChatGroupVO> myGroups(Long userId) {
        return groupMemberMapper.selectList(
                        new QueryWrapper<ChatGroupMember>().lambda().eq(ChatGroupMember::getUserId, userId))
                .stream()
                .map(ChatGroupMember::getGroupId)
                .distinct()
                .map(gid -> {
                    ChatGroup g = groupMapper.selectById(gid);
                    ChatGroupVO vo = new ChatGroupVO();
                    vo.setId(gid);
                    vo.setName(g == null ? "群聊" + gid : g.getName());
                    vo.setMemberCount(groupMemberMapper.selectCount(new QueryWrapper<ChatGroupMember>()
                            .lambda().eq(ChatGroupMember::getGroupId, gid)));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<ChatUserVO> onlineUsers(Long selfId) {
        // 在线 = 在 Redis 路由表里有记录（WS 长连接还活着）。路由表是全局的，所以跨实例可见。
        Set<String> keys = redisTemplate.<String, String>opsForHash()
                .keys(ChatConstants.ROUTE_TABLE_KEY);
        Set<Long> ids = keys.stream().map(Long::valueOf)
                .filter(id -> !id.equals(selfId))   // 自己不给自己发私聊
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        // 批量补昵称头像（Feign 一次调用），按昵称排序方便查找
        Map<Long, UserDTO> users = batchUsers(ids);
        return ids.stream().map(id -> {
            UserDTO u = users.get(id);
            ChatUserVO vo = new ChatUserVO();
            vo.setId(id);
            vo.setName(u == null ? "用户" + id : u.getName());
            vo.setIcon(u == null ? null : u.getIcon());
            return vo;
        }).sorted(Comparator.comparing(ChatUserVO::getName)).collect(Collectors.toList());
    }

    @Override
    public void refreshSensitiveWords() {
        // 本机先刷新，再广播（onBroadcast 收到自己的广播会再 reload 一次，幂等无害）
        dfaWordFilter.reload();
        try {
            Map<String, Object> relay = new HashMap<>(2);
            relay.put("relayType", ChatConstants.RELAY_DFA_REFRESH);
            relay.put("publisher", INSTANCE_ID);
            redisTemplate.convertAndSend(ChatConstants.BROADCAST_CHANNEL,
                    objectMapper.writeValueAsString(relay));
        } catch (Exception e) {
            log.error("违禁词刷新广播失败（本机已刷新）", e);
        }
    }

    // ==================== 私有工具 ====================

    private boolean isGroupMember(Long groupId, Long userId) {
        return groupMemberMapper.selectCount(new QueryWrapper<ChatGroupMember>()
                .lambda()
                .eq(ChatGroupMember::getGroupId, groupId)
                .eq(ChatGroupMember::getUserId, userId)) > 0;
    }

    private ChatMessageVO toVO(ChatMessage message) {
        boolean recalled = Boolean.TRUE.equals(message.getRecalled());
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(message.getId());
        vo.setSenderId(message.getSenderId());
        vo.setMessageType(message.getMessageType());
        vo.setTargetId(message.getTargetId());
        // 已撤回的消息不再下发原文：撤回的语义就是「收回来」，原文只能留在库里
        vo.setContent(recalled ? "" : message.getContent());
        vo.setSentAt(message.getSentAt());
        vo.setRecalled(recalled);
        return vo;
    }

    private void fillSenderInfo(ChatMessageVO vo) {
        UserDTO user = userCache.get(vo.getSenderId(),
                id -> userClient.queryUserById(id));
        if (user != null) {
            vo.setSenderName(user.getName());
            vo.setSenderIcon(user.getIcon());
        }
    }

    /**
     * 批量补发送者资料（历史一页用）。
     * <p>原来对每条消息单独调 fillSenderInfo：一页 30 条消息若有 30 个不同发送者、
     * 且缓存是冷的，就是 30 次 Feign 调用。这里先收集去重后的 senderId，一次批量查。
     */
    private void fillSenderInfo(List<ChatMessageVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        Map<Long, UserDTO> senders = batchUsers(vos.stream()
                .map(ChatMessageVO::getSenderId).distinct().collect(Collectors.toList()));
        vos.forEach(vo -> {
            UserDTO u = senders.get(vo.getSenderId());
            if (u != null) {
                vo.setSenderName(u.getName());
                vo.setSenderIcon(u.getIcon());
            }
        });
    }

    /**
     * 批量取用户资料，优先命中进程内缓存。
     * <p>原来只写缓存不读（每次调用都实打一次 user-service），而 /online 会被 presence
     * 广播频繁触发 —— 「一次上线广播 → 每个客户端一次 /online → 每次都查 user-service」
     * 会把下游放大成广播量的 N 倍。现在只对缓存里没有的那部分发起一次 Feign 调用。
     */
    private Map<Long, UserDTO> batchUsers(Collection<Long> ids) {
        Map<Long, UserDTO> result = new HashMap<>(ids.size());
        List<Long> missed = new ArrayList<>();
        for (Long id : ids) {
            UserDTO cached = userCache.getIfPresent(id);
            if (cached != null) {
                result.put(id, cached);
            } else {
                missed.add(id);
            }
        }
        if (!missed.isEmpty()) {
            for (UserDTO u : userClient.queryUserByIds(missed)) {
                userCache.put(u.getId(), u);
                result.put(u.getId(), u);
            }
        }
        return result;
    }
}
