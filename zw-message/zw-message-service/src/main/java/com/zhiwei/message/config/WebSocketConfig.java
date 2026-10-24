package com.zhiwei.message.config;

import com.zhiwei.authsdk.gateway.util.AuthUtil;
import com.zhiwei.common.domain.R;
import com.zhiwei.common.domain.dto.LoginUserDTO;
import com.zhiwei.message.websocket.ChatWebSocketHandler;
import com.zhiwei.message.websocket.LocalSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 端点配置 + 握手期鉴权。
 *
 * <p>为什么鉴权放在 HandshakeInterceptor 而不是连接后第一帧：
 * HTTP 升级请求在握手阶段就能拿到 query 里的 token，此时拒绝连接，
 * 客户端拿到的是明确的 401，不产生任何已建立的长连接资源；
 * 若放到连接后校验，「未认证连接已占用 session/内存」的窗口始终存在，
 * 且要自己处理「校验失败后踢连接」的清理逻辑。
 *
 * <p>为什么 token 放 query 而不是请求头：浏览器原生 WebSocket API
 * 不允许自定义请求头，唯一能携带凭据的就是握手 URL 的 query 参数。
 * 网关侧 AccountAuthFilter 只读 Authorization header，但 checkAuth 对
 * 未在权限表中注册的路径直接放行，WS 握手路径不在权限表内，可正常路由。
 *
 * <p>端点路径为什么是 /ws：网关路由 Path=/sms/** 带 StripPrefix，
 * /sms/ws 到达本服务时前缀已剥掉；前端以 ws://网关/sms/ws 访问，本地直连则用 /ws。
 */
@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final AuthUtil authUtil;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws")
                .addInterceptors(new AuthHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }

    /**
     * 握手拦截器：从 query 参数 authorization 里取 token 解析用户身份，
     * 解析失败拒绝握手（返回 false = 不升级为 WebSocket 连接）。
     * 解析成功的 userId 放进 handshake attributes，后续 handler 直接取用。
     */
    private class AuthHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                       @NonNull ServerHttpResponse response,
                                       @NonNull WebSocketHandler wsHandler,
                                       @NonNull Map<String, Object> attributes) {
            if (!(request instanceof ServletServerHttpRequest)) {
                return false;
            }
            String token = ((ServletServerHttpRequest) request)
                    .getServletRequest().getParameter("authorization");
            R<LoginUserDTO> r = authUtil.parseToken(token);
            if (r == null || !r.success() || r.getData() == null) {
                log.warn("WS 握手拒绝：token 无效");
                return false;
            }
            Long userId = r.getData().getUserId();
            attributes.put(LocalSessionRegistry.ATTR_USER_ID, userId);
            log.info("WS 握手成功，userId={}", userId);
            return true;
        }

        @Override
        public void afterHandshake(@NonNull ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler,
                                   Exception exception) {
            // 无需处理
        }
    }
}
