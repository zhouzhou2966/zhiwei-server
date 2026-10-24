package com.zhiwei.common.autoconfigure.idempotent.token;

import com.zhiwei.common.autoconfigure.idempotent.IdempotentConstants;
import com.zhiwei.common.autoconfigure.idempotent.IdempotentProperties;
import com.zhiwei.common.autoconfigure.idempotent.storage.IdempotentStorage;

import java.time.Duration;
import java.util.UUID;

/**
 * 一次性令牌的签发与消费
 * <p>令牌模式解决的是幂等键模式覆盖不到的一类重复提交：
 * <b>请求参数完全相同、但用户主观上只想提交一次</b>。
 * 例如用户连点两次"下单"，两次请求的参数一模一样，
 * 用参数推导幂等键无法区分"用户想下两单"还是"用户手抖点两次"，
 * 而令牌在第一次提交时就被消费掉了，第二次必然失败。
 * <p>时序：
 * <pre>
 * 客户端                          服务端
 *   │  GET /idempotent/token  ──▶  生成令牌，写入 Redis（TTL=tokenTimeout）
 *   │  ◀── "3f2a..."                 
 *   │  POST /xxx  (Header: Idempotent-Token=3f2a...)
 *   │                        ──▶  原子删除令牌：删成功=放行，删失败=重复提交
 * </pre>
 * <p>令牌只允许被消费一次，且由 Redis 的原子删除保证多实例下的唯一性。
 **/
public class IdempotentTokenService {

    private final IdempotentStorage storage;
    private final IdempotentProperties properties;

    public IdempotentTokenService(IdempotentStorage storage, IdempotentProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    /**
     * 签发一次性令牌
     *
     * @return 令牌字符串，由客户端在下一次提交时通过请求头带回
     */
    public String issue() {
        String token = UUID.randomUUID().toString().replace("-", "");
        storage.tryAcquire(keyOf(token), IdempotentConstants.TOKEN_OWNER,
                Duration.ofSeconds(properties.getTokenTimeout()));
        return token;
    }

    /**
     * 消费令牌：原子删除，返回 true 表示本次消费有效
     *
     * @param token 客户端携带的令牌
     * @return true 表示令牌存在且已被本次请求消费掉
     */
    public boolean consume(String token) {
        return storage.release(keyOf(token), IdempotentConstants.TOKEN_OWNER);
    }

    /**
     * 归还令牌：业务执行失败时把令牌放回去，让客户端可以用同一个令牌重试。
     * <p>这是一个<b>有意的取舍</b>：
     * <ul>
     *     <li>归还 → 业务失败可重试，用户体验好；代价是"部分成功"的场景下重试可能重复执行</li>
     *     <li>不归还 → 严格一次性，绝不重复；代价是业务一旦失败，客户端必须重新申请令牌</li>
     * </ul>
     * 这里选择归还，因为业务失败通常意味着"什么都没发生"（异常已回滚），
     * 重试是用户合理的诉求；而"部分成功"本身属于业务要处理的边界，不该由幂等框架兜。
     *
     * @param token 需要归还的令牌
     */
    public void giveBack(String token) {
        storage.tryAcquire(keyOf(token), IdempotentConstants.TOKEN_OWNER,
                Duration.ofSeconds(properties.getTokenTimeout()));
    }

    /**
     * 令牌在 Redis 中的完整 key
     */
    public String keyOf(String token) {
        return properties.getKeyPrefix() + IdempotentConstants.TOKEN_KEY_PREFIX + token;
    }
}
