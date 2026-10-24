package com.zhiwei.promotion.utils;

import com.zhiwei.common.exceptions.BizIllegalException;
import org.redisson.api.RLock;

public enum MyLockStrategy {
    SKIP_FAST() {
        /**
         * 快速跳过策略：尝试获取锁，等待时间为0。
         * 如果锁可用则立即获取并返回true；如果锁被占用则立即返回false，不阻塞等待。
         * 调用方应根据返回值决定是否跳过当前业务逻辑。
         */
        @Override
        public boolean tryLock(RLock lock, MyLock prop) throws InterruptedException {
            return lock.tryLock(0, prop.leaseTime(), prop.unit());
        }
    },
    FAIL_FAST() {
        @Override
        public boolean tryLock(RLock lock, MyLock prop) throws InterruptedException {
            boolean isLock = lock.tryLock(0, prop.leaseTime(), prop.unit());
            if (!isLock) {
                throw new BizIllegalException("请求太频繁了");
            }
            return true;
        }
    },
    KEEP_TRYING() {
        @Override
        public boolean tryLock(RLock lock, MyLock prop) throws InterruptedException {
            lock.lock(prop.leaseTime(), prop.unit());
            return true;
        }
    },
    SKIP_AFTER_RETRY_TIMEOUT() {
        @Override
        public boolean tryLock(RLock lock, MyLock prop) throws InterruptedException {
            return lock.tryLock(prop.waitTime(), prop.leaseTime(), prop.unit());
        }
    },
    FAIL_AFTER_RETRY_TIMEOUT() {
        @Override
        public boolean tryLock(RLock lock, MyLock prop) throws InterruptedException {
            boolean isLock = lock.tryLock(prop.waitTime(), prop.leaseTime(), prop.unit());
            if (!isLock) {
                throw new BizIllegalException("请求太频繁了");
            }
            return true;
        }
    };

    public abstract boolean tryLock(RLock lock, MyLock myLock) throws InterruptedException;
}