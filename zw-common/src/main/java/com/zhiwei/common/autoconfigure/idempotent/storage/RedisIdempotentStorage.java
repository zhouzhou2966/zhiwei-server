package com.zhiwei.common.autoconfigure.idempotent.storage;

import com.zhiwei.common.autoconfigure.idempotent.IdempotentConstants;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的幂等位存储
 * <p>三个关键操作各自的原子性保证方式：
 * <ol>
 *     <li>抢占 → 复用 Redis 原生的 {@code SET key value NX PX ttl}（Redisson 的 trySet）</li>
 *     <li>标记成功 / 释放 → 必须用 Lua 脚本，因为都需要"先比较 owner 再改写"，
 *         拆成两条命令就会有中间态被并发穿插</li>
 * </ol>
 * <p>为什么不用 Redisson 的 {@code RLock}？因为 RLock 是可重入锁，重入判断依据是"线程 + 客户端 id"，
 * 同一个线程重复加锁会成功，恰恰破坏了我们想要的幂等语义。幂等要的是"业务只执行一次"，
 * 而不是"同一线程可重入"，所以这里用最朴素的 key-value 语义自己实现。
 **/
public class RedisIdempotentStorage implements IdempotentStorage {

    /**
     * 标记成功：仅当持有者仍是 owner 时才改写 value 与过期时间
     */
    private static final String MARK_SUCCESS_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  redis.call('psetex', KEYS[1], ARGV[2], ARGV[3]) " +
            "  return 1 " +
            "end " +
            "return 0";

    /**
     * 释放：仅当持有者仍是 owner 时才删除
     */
    private static final String RELEASE_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "end " +
            "return 0";

    private final RedissonClient redissonClient;

    public RedisIdempotentStorage(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public boolean tryAcquire(String key, String owner, Duration ttl) {
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        return bucket.trySet(owner, ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean markSuccess(String key, String owner, Duration ttl, String value) {
        Long r = eval(MARK_SUCCESS_LUA, key, owner, ttl.toMillis(), value);
        return r != null && r > 0;
    }

    @Override
    public boolean release(String key, String owner) {
        Long r = eval(RELEASE_LUA, key, owner);
        return r != null && r > 0;
    }

    @Override
    public String get(String key) {
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        return bucket.get();
    }

    /**
     * 执行 Lua 脚本。Redis 的 EVAL 只接受字符串参数，这里统一做一次转换，
     * 避免调用方漏转导致参数变成二进制而匹配不上。
     */
    private Long eval(String lua, String key, Object... args) {
        Object[] params = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            params[i] = String.valueOf(args[i]);
        }
        return redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                lua,
                RScript.ReturnType.INTEGER,
                Collections.singletonList((Object) key),
                params);
    }

    /**
     * 判断幂等位的值是否代表"已完成"
     */
    public static boolean isSuccess(String value) {
        return value != null && value.startsWith(IdempotentConstants.SUCCESS_MARK);
    }

    /**
     * 从幂等位的值中提取业务返回值的 JSON
     */
    public static String extractResult(String value) {
        if (!isSuccess(value)) {
            return null;
        }
        String json = value.substring(IdempotentConstants.SUCCESS_MARK.length());
        return json.isEmpty() ? null : json;
    }
}