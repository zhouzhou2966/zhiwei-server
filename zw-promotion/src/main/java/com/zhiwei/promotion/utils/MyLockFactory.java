package com.zhiwei.promotion.utils;


import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

import static com.zhiwei.promotion.utils.MyLockType.*;

/**
 * 锁工厂类，用于根据不同的锁类型创建对应的Redisson锁对象
 * 使用了Spring的Component注解，将其声明为Spring容器中的一个Bean
 * 使用了Lombok的RequiredArgsConstructor注解，自动生成包含final字段的构造器
 */
@Component
public class MyLockFactory {
    // 锁处理器映射表，存储不同锁类型对应的锁创建函数
    private final Map<MyLockType, Function<String, RLock>> lockHandlers;

    public MyLockFactory(RedissonClient redissonClient) {

        this.lockHandlers = new EnumMap<>(MyLockType.class);
        lockHandlers.put(RE_ENTRANT_LOCK, redissonClient::getLock);  // 重入锁
        lockHandlers.put(FAIR_LOCK, redissonClient::getFairLock);  // 公平锁
        lockHandlers.put(READ_LOCK, name -> redissonClient.getReadWriteLock(name).readLock());  // 读锁
        lockHandlers.put(WRITE_LOCK, name -> redissonClient.getReadWriteLock(name).writeLock());  // 写锁
    }
    // 根据锁类型创建锁对象
    public RLock getLock(String name, MyLockType type) {
        return lockHandlers.get(type).apply(name);
    }
}
