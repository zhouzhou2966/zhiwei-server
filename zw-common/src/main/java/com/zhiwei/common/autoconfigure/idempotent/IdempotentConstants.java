package com.zhiwei.common.autoconfigure.idempotent;

/**
 * 幂等框架常量
 **/
public interface IdempotentConstants {

    /**
     * 完成标记前缀
     * <p>幂等位的 value 有两种形态，靠这个前缀区分：
     * <ul>
     *     <li>处理中 → value 是本次请求的 owner（UUID）</li>
     *     <li>已完成 → value 以该前缀开头，其后可选地跟随业务返回值的 JSON</li>
     * </ul>
     */
    String SUCCESS_MARK = "SUCCESS:";

    /**
     * 令牌模式下幂等位的固定持有者。
     * <p>令牌不区分调用方，任何拿到令牌的请求都可以消费它，因此 owner 是常量。
     */
    String TOKEN_OWNER = "token";

    /**
     * 令牌在 Redis 中的 key 前缀（拼在全局 keyPrefix 之后）
     */
    String TOKEN_KEY_PREFIX = "token:";

    /**
     * 幂等键 SPEL 中可直接使用的内置变量名：当前登录用户 id
     * <p>方法参数里往往拿不到用户身份（它来自 ThreadLocal 的 {@code UserContext}），
     * 但幂等键几乎总是需要它，例如"同一用户对同一课程只能报名一次"。
     * 因此框架把它作为内置变量注入 SPEL 上下文，写法：{@code order:enroll:#{userId}:#{courseId}}
     */
    String VAR_USER_ID = "userId";
}
