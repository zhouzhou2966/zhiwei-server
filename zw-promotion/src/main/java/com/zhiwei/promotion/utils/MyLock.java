package com.zhiwei.promotion.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)   // 注解作用在方法上
public @interface MyLock {
    String name(); //   锁的名称

    long waitTime() default 1;//等待时间

    long leaseTime() default -1;//租约时间

    TimeUnit unit() default TimeUnit.SECONDS;//时间单位

    MyLockType type() default MyLockType.RE_ENTRANT_LOCK;  // 锁类型，默认重入锁

    MyLockStrategy lockStrategy() default MyLockStrategy.FAIL_AFTER_RETRY_TIMEOUT;//锁获取策略 默认失败后重试超时
}
