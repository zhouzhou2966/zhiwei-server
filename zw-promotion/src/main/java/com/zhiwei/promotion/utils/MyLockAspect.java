package com.zhiwei.promotion.utils;


import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RLock;

import org.springframework.core.Ordered;

import org.springframework.stereotype.Component;


/**
 * 使用Redisson实现的分布式锁切面类
 * 通过AOP方式为方法添加分布式锁功能
 * 使用@RequiredArgsConstructor注解自动注入RedissonClient
 */
@RequiredArgsConstructor
@Component
@Aspect
public class MyLockAspect implements Ordered {
    private final MyLockFactory lockFactory;

    /**
     * 尝试获取锁并执行方法，使用Redisson实现分布式锁
     *
     * @param pjp    切面连接点，用于执行目标方法
     * @param myLock 自定义锁注解，包含锁名称、等待时间、租约时间等信息
     * @return 目标方法的执行结果
     * @throws Throwable 可能抛出的异常
     */

    @Around("@annotation(myLock)") // 指定切点，匹配所有带有MyLock注解的方法
    public Object tryLock(ProceedingJoinPoint pjp, MyLock myLock) throws Throwable {
        RLock lock = lockFactory.getLock(myLock.name(), myLock.type());// 根据锁类型创建锁对象
        // 尝试获取锁，设置等待时间、租约时间和时间单位
        boolean isLock = myLock.lockStrategy().tryLock(lock, myLock);
        // 如果获取锁失败，抛出业务异常
        if (!isLock) {
            return null;
        }
        try {

            // 执行目标方法并返回结果
            return pjp.proceed();
        } finally {
            // 确保锁被释放，避免死锁
            lock.unlock();
        }
    }


    @Override
    public int getOrder() {
        return 0;
    }
}
