package com.zhiwei.auth.common.constants;

import java.time.Duration;

public class JwtConstants {
    public static final String PAYLOAD_USER_KEY = "user";
    public static final String PAYLOAD_JTI_KEY = "jti";

    public static final String JWT_REDIS_KEY_PREFIX = "jwt:uid:";
    // token过期时间，测试期间改为 1天，正常是5分钟
    // 2026-10-12：access token 5 分钟 + refresh token 30 分钟的组合，
    // 导致只要 30 分钟不操作就被迫重新登录（access 过期后 refresh 也过期，无法续期）。
    // 开发/演示环境调为 access 2 小时、refresh 7 天，与前端「7天免登录」口径一致。
    public static final Duration JWT_TOKEN_TTL = Duration.ofHours(2);
    // public static final Duration JWT_TOKEN_TTL = Duration.ofMinutes(5);
    // public static final Duration JWT_TOKEN_TTL = Duration.ofMinutes(60 * 24);
    public static final Duration JWT_REFRESH_TTL = Duration.ofDays(7);
    // public static final Duration JWT_REFRESH_TTL = Duration.ofMinutes(30);

    public static final Duration JWT_REMEMBER_ME_TTL = Duration.ofDays(7);

    /**
     * 「7天免登录」勾选时 access-token 的有效期。
     * <p>不勾选时仍走 JWT_TOKEN_TTL（2 小时）：免登录是用户显式选择的能力，
     * 不能默认给所有人发一个 7 天有效的 access-token。
     */
    public static final Duration JWT_REMEMBER_ME_ACCESS_TTL = Duration.ofDays(7);

    public static final String JWT_ALGORITHM = "rs256";
    public static final String AUTHORIZATION_HEADER = "authorization";
    public static final String REFRESH_HEADER = "refresh";
    public static final String ADMIN_REFRESH_HEADER = "admin-refresh";

    public static final String USER_HEADER = "user-info";

    /* 权限缓存 KEY  begin */
    public static final String AUTH_PRIVILEGE_KEY = "auth:privileges";
    public static final String AUTH_PRIVILEGE_VERSION_KEY = "version";
    public static final String LOCK_AUTH_PRIVILEGE_KEY = "lock:auth:privileges";
    /* 权限缓存 KEY  end */

}
