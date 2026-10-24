package com.zhiwei.promotion.config;


import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;


import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;


@Configuration
@Slf4j
public class PromotionConfig {

    @Bean
    public Executor generateCouponExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);  //核心线程数
        executor.setMaxPoolSize(5);      //最大线程数
        executor.setQueueCapacity(200);  //队列容量
        executor.setThreadNamePrefix("exchange-code-handler-");  //线程名称前缀
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());  //拒绝策略：由调用者线程执行
        executor.setWaitForTasksToCompleteOnShutdown(true);  //关闭时等待任务完成
        executor.setAwaitTerminationSeconds(60);             //最多等待60秒
        executor.initialize();
        return executor;
    }
    @Bean
    public Executor discountSolutionExecutor() {
        ThreadPoolTaskExecutor discountSolutionExecutor = new ThreadPoolTaskExecutor();
        // CPU 密集型任务，线程数 = CPU 核数(12) + 1，避免上下文切换
        discountSolutionExecutor.setCorePoolSize(13);
        // 最大线程数与核心线程数一致
        discountSolutionExecutor.setMaxPoolSize(13);
        // 队列容量（20并发 × 150任务 ≈ 3000，避免 CallerRuns 溢出）
        discountSolutionExecutor.setQueueCapacity(3000);
        // 配置线程池中的线程的名称前缀
        discountSolutionExecutor.setThreadNamePrefix("discount-solution-calculator-");
        // 拒绝策略：由调用者线程执行，避免任务丢失
        discountSolutionExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        discountSolutionExecutor.setWaitForTasksToCompleteOnShutdown(true);  //关闭时等待任务完成
        discountSolutionExecutor.setAwaitTerminationSeconds(60);             //最多等待60秒
        discountSolutionExecutor.initialize();
        return discountSolutionExecutor;
    }
}