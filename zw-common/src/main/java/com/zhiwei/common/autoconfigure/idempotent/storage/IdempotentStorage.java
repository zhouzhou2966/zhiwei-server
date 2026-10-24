package com.zhiwei.common.autoconfigure.idempotent.storage;

import java.time.Duration;

/**
 * 幂等位存储抽象
 * <p>把"幂等状态"的读写收敛到这一个接口，好处有三：
 * <ol>
 *     <li>换存储不动切面逻辑：当前是 Redis（多实例共享），单机场景可以换成 Caffeine</li>
 *     <li>单元测试不需要起 Redis，mock 掉即可</li>
 *     <li>语义集中：所有"原子性"要求都写在实现里，切面不关心底层怎么保证</li>
 * </ol>
 * <p>所有写操作都要求<b>按持有者校验</b>（owner），目的是防止"前一个请求超时释放了后一个请求的幂等位"。
 **/
public interface IdempotentStorage {

    /**
     * 尝试抢占幂等位
     * <p>要求原子性：多实例并发调用时，有且仅有一个返回 true。
     *
     * @param key   幂等键（已带前缀）
     * @param owner 本次请求的持有者标识，用于后续释放时的归属校验
     * @param ttl   幂等位存活时长
     * @return true 表示抢占成功，即首次请求，可以执行业务；false 表示已有请求占用
     */
    boolean tryAcquire(String key, String owner, Duration ttl);

    /**
     * 将幂等位标记为"处理成功"，并可选写入业务返回值
     * <p>要求原子性：仅当当前持有者仍是 owner 时才允许改写。
     * 如果此处不校验 owner，会出现这样的错序：
     * A 执行业务超时 → 幂等位过期 → B 抢占成功 → A 才执行完并写入标记，把 B 的幂等位覆盖成了"成功"。
     *
     * @param key    幂等键
     * @param owner  持有者标识
     * @param ttl    标记的存活时长
     * @param result 业务返回值的 JSON 序列化结果，可为 null
     * @return true 表示标记成功
     */
    boolean markSuccess(String key, String owner, Duration ttl, String result);

    /**
     * 释放幂等位（仅当持有者是 owner 时），用于业务失败后允许客户端重试
     * <p>要求原子性：校验与删除必须在同一次 Redis 往返中完成。
     * 若拆成"先 GET 判断、再 DEL"，两次调用之间幂等位可能刚好过期并被别的请求抢占，
     * 此时 DEL 就会误删他人的幂等位。
     *
     * @return true 表示确实由本次请求释放；false 表示幂等位已不属于 owner（已过期或被他人占用）
     */
    boolean release(String key, String owner);

    /**
     * 读取幂等位的当前值，用于判断重复请求处于"处理中"还是"已完成"
     *
     * @return 幂等位不存在时返回 null
     */
    String get(String key);
}
