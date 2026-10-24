package com.zhiwei.api.constants;

/**
 * 用户资料（昵称 / 头像 / 性别）变更的跨服务契约。
 *
 * <p>为什么需要它：消息服务推送每条消息都要带发送者的昵称头像，为此在进程内用
 * Caffeine 缓存了用户资料（否则每条消息同步查一次 user-service 会把它打成热点）。
 * 但「进程内缓存 + 固定 TTL」有个绕不开的问题 —— 用户在个人设置里换了头像，
 * 聊天里最多要等 TTL（5 分钟）才可见，而且**刷新浏览器没用**，因为缓存不在浏览器里。
 *
 * <p>所以这里定一个「资料变更事件」频道：user 服务落库成功后广播一次，
 * 消息服务收到就失效对应用户的缓存项，并把新资料推给在线客户端，做到**零延迟**可见。
 * 放在 zw-api 是因为它是 user 与 message 都依赖的模块，属于天然的跨服务契约层。
 *
 * <p><b>为什么事件里要带 name/icon 快照，而不是只带 userId</b>：
 * 按「缓存失效事件只传 key」的教科书做法实现后实测踩坑 —— 订阅方是在 Redis 监听线程里
 * 处理事件的，那里**没有用户上下文**（{@code UserContext} 是 ThreadLocal，
 * 由 HTTP 请求链路上的 UserInfoInterceptor 填充），于是它回查 user-service 时
 * {@code FeignRelayUserInterceptor} 带不上 user-info 头，直接被下游判 401，
 * fallback 返回 null，帧根本推不出去。
 * 所以改成由发布方把「刚写进库的」昵称头像一并带上：
 * <ul>
 *   <li>发布方本来就在请求线程里、手里就有最新值，不额外产生任何查询；</li>
 *   <li>订阅方不用再回查，也就不会在监听线程里做阻塞 I/O（拖住所有频道的事件处理）；</li>
 *   <li>快照不存在「过期」问题 —— 事件是在落库成功后同步发布的，pub/sub 是同步投递，
 *       真有更晚的写入必然再发一次事件，后到的覆盖先到的，顺序天然正确。</li>
 * </ul>
 * 注意：<b>失效缓存仍然是主路径</b>，快照只用于「主动推送」这个体验增强；
 * 快照里的字段允许为 null（表示该字段本次没改），订阅方据此跳过对应字段。
 */
public interface UserProfileConstants {

    /**
     * 用户资料变更事件频道（Redis pub/sub）。
     * <p>发布方：zw-user（资料落库成功后）；
     * <p>订阅方：所有 zw-message-service 实例（缓存是进程内的，必须每个实例都收到）。
     * <p>消息体：{@code {"userId":123,"name":"jack","icon":"/img-ali/xxx.png"}} ——
     * {@code userId} 必有；{@code name} / {@code icon} 可能为 null（本次未改该字段）。
     */
    String PROFILE_CHANGED_CHANNEL = "user:profile:changed";
}
