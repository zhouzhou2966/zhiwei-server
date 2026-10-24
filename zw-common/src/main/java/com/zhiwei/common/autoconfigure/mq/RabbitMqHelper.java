package com.zhiwei.common.autoconfigure.mq;

import cn.hutool.core.lang.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.zhiwei.common.constants.Constant.REQUEST_ID_HEADER;

@Slf4j
public class RabbitMqHelper {

    private final RabbitTemplate rabbitTemplate;
    private final MessagePostProcessor processor = new BasicIdMessageProcessor();
    private final ThreadPoolTaskExecutor executor;

    public RabbitMqHelper(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        executor = new ThreadPoolTaskExecutor();
        //配置核心线程数
        executor.setCorePoolSize(10);
        //配置最大线程数
        executor.setMaxPoolSize(15);
        //配置队列大小
        executor.setQueueCapacity(99999);
        //配置线程池中的线程的名称前缀
        executor.setThreadNamePrefix("mq-async-send-handler-");

        // 设置拒绝策略：当pool已经达到max size的时候，如何处理新任务
        // CALLER_RUNS：不在新线程中执行任务，而是有调用者所在的线程来执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);  //关闭时等待任务完成
        executor.setAwaitTerminationSeconds(60);             //最多等待60秒
        //执行初始化
        executor.initialize();
    }

    /**
     * 根据exchange和routingKey发送消息，并等待 Broker 确认（Publisher Confirm）
     * <p>
     * 需要启用 spring.rabbitmq.publisher-confirm-type=correlated，
     * 若未启用确认则退化为普通发送（不等待确认，和之前行为一致）。
     *
     * @return true=消息被 Broker 确认接收，false=发送失败或 Broker 返回 nack
     */
    public <T> boolean send(String exchange, String routingKey, T t) {
        log.debug("准备发送消息，exchange：{}， RoutingKey：{}， message：{}", exchange, routingKey, t);
        try {
            String id = UUID.randomUUID().toString(true);
            CorrelationData correlationData = new CorrelationData(id);
            rabbitTemplate.convertAndSend(exchange, routingKey, t, processor, correlationData);

            // 只有真的开了 publisher-confirm，future 才会完成。
            // 没开的时候直接返回，否则每个请求都要白等 500ms 超时 ——
            // 领券这种热点接口等于凭空多出半秒 RT，并发一高线程池就被占满。
            if (!isPublisherConfirmsEnabled()) {
                return true;
            }
            // 等待 Broker 确认（500ms 超时）
            try {
                CorrelationData.Confirm confirm = correlationData.getFuture().get(500, TimeUnit.MILLISECONDS);
                if (!confirm.isAck()) {
                    log.warn("Broker 返回 nack，exchange={}, routingKey={}, reason={}",
                            exchange, routingKey, confirm.getReason());
                    return false;
                }
            } catch (TimeoutException e) {
                log.warn("Publisher Confirm 超时，exchange={}, routingKey={}", exchange, routingKey);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("发送消息失败，exchange：{}， RoutingKey：{}， message：{}", exchange, routingKey, t, e);
            return false;
        }
    }

    /**
     * 判断当前连接工厂是否开启了 Publisher Confirm
     * <p>没开的时候 {@code correlationData.getFuture()} 永远不会完成，
     * 之前靠「超时视为成功」兜底，代价是每个请求固定 +500ms。</p>
     */
    private boolean isPublisherConfirmsEnabled() {
        ConnectionFactory factory = rabbitTemplate.getConnectionFactory();
        if (factory instanceof CachingConnectionFactory) {
            return ((CachingConnectionFactory) factory).isPublisherConfirms();
        }
        return false;
    }

    /**
     * 根据exchange和routingKey发送消息，并且可以设置延迟时间
     */
    public <T> void sendDelayMessage(String exchange, String routingKey, T t, Duration delay) {
        String id = UUID.randomUUID().toString(true);
        CorrelationData correlationData = new CorrelationData(id);
        rabbitTemplate.convertAndSend(exchange, routingKey, t, new DelayedMessageProcessor(delay), correlationData);
    }


    /**
     * 根据exchange和routingKey 异步发送消息，并指定一个延迟时间
     *
     * @param exchange   交换机
     * @param routingKey 路由KEY
     * @param t          数据
     * @param <T>        数据类型
     */
    public <T> void sendAsync(String exchange, String routingKey, T t, Long time) {
        String requestId = MDC.get(REQUEST_ID_HEADER);
        CompletableFuture.runAsync(() -> {
            try {
                MDC.put(REQUEST_ID_HEADER, requestId);
                // 发送延迟消息
                if (time != null && time > 0) {
                    sendDelayMessage(exchange, routingKey, t, Duration.ofMillis(time));
                } else {
                    send(exchange, routingKey, t);
                }
            } catch (Exception e) {
                log.error("推送消息异常，t:{},", t, e);
            }
        }, executor);
    }


    /**
     * 根据exchange和routingKey 异步发送消息
     *
     * @param exchange   交换机
     * @param routingKey 路由KEY
     * @param t          数据
     * @param <T>        数据类型
     */
    public <T> void sendAsync(String exchange, String routingKey, T t) {
        sendAsync(exchange, routingKey, t, null);
    }

    /**
     * 根据exchange和routingKey 异步发送消息，失败自动重试，重试耗尽后执行降级回调
     *
     * @param exchange   交换机
     * @param routingKey 路由KEY
     * @param t          数据
     * @param retries    重试次数（不含首次发送）
     * @param onFailure  重试耗尽后的降级回调（如写 Redis 失败集合等待补偿），可为 null
     * @param <T>        数据类型
     */
    public <T> void sendAsyncWithRetry(String exchange, String routingKey, T t, int retries, Runnable onFailure) {
        String requestId = MDC.get(REQUEST_ID_HEADER);
        CompletableFuture.runAsync(() -> {
            try {
                MDC.put(REQUEST_ID_HEADER, requestId);
                for (int i = 0; i <= retries; i++) {
                    if (send(exchange, routingKey, t)) {
                        return;
                    }
                }
                log.error("异步发送消息失败，已重试 {} 次，exchange={}, routingKey={}", retries, exchange, routingKey);
                if (onFailure != null) {
                    try {
                        onFailure.run();
                    } catch (Exception e) {
                        log.error("消息发送失败的降级回调执行失败，exchange={}, routingKey={}", exchange, routingKey, e);
                    }
                }
            } catch (Exception e) {
                log.error("推送消息异常，t:{}", t, e);
            } finally {
                MDC.clear();
            }
        }, executor);
    }

}