package com.zhiwei.user.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiwei.api.constants.UserProfileConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户资料变更广播器。
 *
 * <p>用户改了昵称/头像后，除了本服务落库，还要通知「缓存了用户资料的其他服务」把缓存删掉。
 * 目前唯一的订阅方是 zw-message-service —— 它为了不给 user-service 造成热点，
 * 在进程内用 Caffeine 缓存了发送者的昵称头像（TTL 5 分钟）。没有这个广播的话，
 * 用户换完头像回到聊天页看到的还是旧头像，而且刷新浏览器也不会好。
 *
 * <p>为什么走 Redis pub/sub 而不是 MQ：
 * <ul>
 *   <li>「缓存失效」是尽力而为的语义 —— 通知丢了最多退化成等 TTL 自然过期，
 *       不像业务消息那样需要持久化与消费确认；</li>
 *   <li>订阅方是「所有实例」，pub/sub 天然广播，不需要像 MQ 那样每个实例建队列。</li>
 * </ul>
 * <p>已知取舍：Redis 不可用期间的通知会丢。此时降级为「等 TTL 过期」，
 * 不会造成数据错误，只是资料变更的可见性退回到 5 分钟窗口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileChangeNotifier {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 广播「某用户的资料变了」。
     *
     * <p><b>为什么要把 name/icon 一起带上（而不是只发 userId 让订阅方回查）</b>：
     * 订阅方是在 Redis 监听线程里处理事件的，那里没有用户上下文（{@code UserContext} 是
     * ThreadLocal，由 HTTP 请求链路上的拦截器填充），回查 user-service 会因缺
     * user-info 头被判 401，帧就推不出去了。而发布方本来就在请求线程里、手里就有最新值，
     * 顺带带上零成本，还省掉了「每个订阅实例各查一次」的放大。
     *
     * <p>{@code name} / {@code icon} 允许为 null，表示本次没有改该字段
     * （例如只换头像时前端不传昵称）—— 订阅方据此跳过该字段的更新。
     *
     * <p>刻意不抛异常：缓存失效失败不该把「改昵称」这个业务操作一起回滚掉。
     */
    public void notifyChanged(Long userId, String name, String icon) {
        if (userId == null) {
            return;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>(4);
            event.put("userId", userId);
            event.put("name", name);
            event.put("icon", icon);
            stringRedisTemplate.convertAndSend(
                    UserProfileConstants.PROFILE_CHANGED_CHANNEL,
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("用户资料变更广播失败 userId={}（不影响资料落库，缓存将在 TTL 到期后自然刷新）",
                    userId, e);
        }
    }
}
