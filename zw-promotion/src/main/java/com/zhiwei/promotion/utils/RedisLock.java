package com.zhiwei.promotion.utils;


import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class RedisLock {
    private final String key;
    private final StringRedisTemplate redisTemplate;

    public boolean tryLock(long timeout, TimeUnit unit) {
        String value = Thread.currentThread().getName();
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
        return Boolean.TRUE.equals(success);
    }

    public void unlock() {
        redisTemplate.delete(key);
    }
}
