package com.zhiwei.message.websocket;

import com.zhiwei.api.constants.UserProfileConstants;
import com.zhiwei.message.constants.ChatConstants;
import com.zhiwei.message.service.IChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis pub/sub 跨实例转发的订阅端配置。
 *
 * <p>为什么用 pub/sub 而不是 Stream / MQ：
 * <ul>
 *   <li>推送是「尽力而为」语义——在线推不到就掉，离线靠位点补拉兜底，
 *       不需要 MQ 的持久化与消费确认；</li>
 *   <li>pub/sub 是 Redis 原生能力，无额外组件；fire-and-forget 正合适。</li>
 * </ul>
 * <p>已知取舍：Redis 宕机期间的广播会丢，但消息已落库，
 * 客户端重连后按 ack 位点补拉即可恢复一致性。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisChatRelayConfig {

    private final IChatService chatService;

    @Bean
    public RedisMessageListenerContainer chatRelayListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(
                new MessageListenerAdapter(new BroadcastDelegator(chatService)),
                new ChannelTopic(ChatConstants.BROADCAST_CHANNEL));
        // 用户资料（昵称/头像）变更 → 失效本实例的进程内缓存，做到变更零延迟可见
        container.addMessageListener(
                new MessageListenerAdapter(new ProfileChangedDelegator(chatService)),
                new ChannelTopic(UserProfileConstants.PROFILE_CHANGED_CHANNEL));
        return container;
    }

    /**
     * 委托对象实现 {@link org.springframework.data.redis.connection.MessageListener} 时，
     * MessageListenerAdapter 会直接反射回调 onMessage(Message, byte[])，不走「方法名约定」那条路；
     * 这里仍然只做一层薄委托：把 Redis Message 的字节体转成 String 交给业务层。
     */
    private static class BroadcastDelegator
            implements org.springframework.data.redis.connection.MessageListener {

        private final IChatService chatService;

        BroadcastDelegator(IChatService chatService) {
            this.chatService = chatService;
        }

        @Override
        public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
            try {
                chatService.onBroadcast(new String(message.getBody()));
            } catch (Exception e) {
                // 广播处理失败不能拖垮订阅线程，记日志由位点补拉兜底
                log.error("处理跨实例广播失败", e);
            }
        }
    }

    /**
     * 「用户资料变更」事件的委托处理。
     *
     * <p>为什么要单独一个订阅者：缓存是**进程内**的（Caffeine），所以每个
     * message-service 实例都必须收到事件、各自失效自己的那份；漏掉任何一个实例
     * 都会出现「A 实例刷新了、B 实例还是旧头像」的不一致。
     * <p>每个实例还负责把自己收到的这份变更推给「连在自己身上」的在线客户端 ——
     * 事件本身已经广播到了所有实例，所以不需要再走 chat:relay 转发一次。
     */
    private static class ProfileChangedDelegator
            implements org.springframework.data.redis.connection.MessageListener {

        private final IChatService chatService;

        ProfileChangedDelegator(IChatService chatService) {
            this.chatService = chatService;
        }

        @Override
        public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
            try {
                chatService.onProfileChanged(new String(message.getBody()));
            } catch (Exception e) {
                // 缓存失效失败不影响资料落库（已成功），最多等到 TTL 自然过期，不能拖垮订阅线程
                log.warn("处理用户资料变更事件失败", e);
            }
        }
    }
}
