package com.zhiwei.gateway.filter;

import com.zhiwei.authsdk.gateway.util.AuthUtil;
import com.zhiwei.common.domain.R;
import com.zhiwei.common.domain.dto.LoginUserDTO;
import com.zhiwei.gateway.config.AuthProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.zhiwei.auth.common.constants.JwtConstants.AUTHORIZATION_HEADER;
import static com.zhiwei.auth.common.constants.JwtConstants.USER_HEADER;

@Component
public class AccountAuthFilter implements GlobalFilter, Ordered {

    private final AuthUtil authUtil;
    private final AuthProperties authProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public AccountAuthFilter(AuthUtil authUtil, AuthProperties authProperties) {
        this.authUtil = authUtil;
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1.获取请求request信息
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethodValue();
        // ⚠️ 这里必须剥掉网关路由前缀再匹配。
        // 权限表（Redis auth:privileges）里存的是服务内部路径，形如 GET:/courses/page；
        // 而网关拿到的是带前缀的 GET:/cs/courses/page。不剥前缀时
        // AntPathMatcher 恒不匹配 → findMatchPath 返回 null → checkAuth 判定「该路径无权限限制」
        // → 直接放行。表现就是 16 条管理端权限规则全是死配置，任何登录用户都能调管理端接口。
        String path = stripRoutePrefix(request.getPath().toString());
        String antPath = method + ":" + path;

        // 2.判断是否是无需登录的路径
        if(isExcludePath(antPath)){
            // 直接放行
            return chain.filter(exchange);
        }

        // 3.尝试获取用户信息
        List<String> authHeaders = exchange.getRequest().getHeaders().get(AUTHORIZATION_HEADER);
        String token = authHeaders == null ? "" : authHeaders.get(0);
        R<LoginUserDTO> r = authUtil.parseToken(token);

        // 4.如果用户是登录状态，尝试更新请求头，传递用户信息
        if(r.success()){
            exchange.mutate()
                    .request(builder -> builder.header(USER_HEADER, r.getData().getUserId().toString()))
                    .build();
        }

        // 5.校验权限
        authUtil.checkAuth(antPath, r);

        // 6.放行
        return chain.filter(exchange);
    }

    /**
     * 去掉网关路由前缀：/cs/courses/page → /courses/page
     * <p>
     * 网关所有路由都是 {@code Path=/xx/**} + {@code StripPrefix=1} 的组合，
     * 所以第一个路径段恒为路由前缀，去掉它就是下游服务看到的真实路径。
     * 只有一个路径段时（如 /cs）原样返回，避免 substring 越界。
     */
    private String stripRoutePrefix(String path) {
        int idx = path.indexOf('/', 1);
        return idx > 0 ? path.substring(idx) : path;
    }

    private boolean isExcludePath(String antPath) {
        for (String pathPattern : authProperties.getExcludePath()) {
            if(antPathMatcher.match(pathPattern, antPath)){
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        // 必须早于 RateLimitFilter（HIGHEST_PRECEDENCE + 20）执行：
        // 限流要按用户维度计数，而用户身份正是本过滤器解析 token 后写入 USER_HEADER 的。
        // 原先的 1000 会让限流过滤器先跑，KeyResolver 只能退化成按 IP 限流，维度就错了。
        // 仍然排在 RequestIdRelayFilter（HIGHEST_PRECEDENCE）之后，保证日志里能带上 requestId。
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
