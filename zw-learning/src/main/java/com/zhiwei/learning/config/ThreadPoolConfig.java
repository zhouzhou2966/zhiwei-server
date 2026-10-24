package com.zhiwei.learning.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@Slf4j
public class ThreadPoolConfig {

    @Bean("asyncServiceExecutor")
    public ThreadPoolTaskExecutor asyncServiceExecutor() {
        log.info("初始化学习服务异步线程池");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：同时处理并行远程调用的线程数
        executor.setCorePoolSize(10);
        // 最大线程数：应对突发流量
        executor.setMaxPoolSize(20);
        // 队列容量：缓冲等待执行的任务
        executor.setQueueCapacity(10000);
        // 线程名前缀：便于日志定位
        executor.setThreadNamePrefix("learning-async-");
        // 拒绝策略：由调用者线程执行，避免任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);  //关闭时等待任务完成
        executor.setAwaitTerminationSeconds(60);             //最多等待60秒
        executor.initialize();
        return executor;
    }
}