package com.zhiwei.common.autoconfigure.mq;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;

import java.time.Duration;

public class DelayedMessageProcessor extends BasicIdMessageProcessor {

    /**
     * 标记「这条消息是延迟消息」的自定义头，给发送端的 ReturnsCallback 分流用。
     * <p>
     * 为什么不能直接靠 {@code x-delay} 判断：{@code x-delayed-message} 插件本身不会删它
     * （用原生 amqp-client 探针验证过，退回消息的 AMQP headers 里 {@code x-delay} 还在），
     * 但 Spring 的 {@code DefaultMessagePropertiesConverter.fromMessage} 会把它从 headers
     * 挪进 {@code MessageProperties.receivedDelay} 字段。于是走 Spring 的
     * {@code ReturnsCallback} 时 {@code getHeader("x-delay")} 恒为 null，延迟消息全被
     * 误报成「真路由失败」（2026-10-21 实测）。
     * </p>
     * <p>
     * 自定义头不会被转换器特殊处理，会原样留在 headers 里，所以另打一个标记作为
     * {@code getReceivedDelay()} 之外的第二个信号。
     * </p>
     */
    public static final String DELAYED_HEADER = "x-zw-delayed";

    private final long delay;

    public DelayedMessageProcessor(Duration delay) {
        this.delay = delay.toMillis();
    }

    @Override
    public Message postProcessMessage(Message message) throws AmqpException {
        // 1.添加消息id
        super.postProcessMessage(message);
        // 2.添加延迟时间（Spring 接收侧会把它挪到 MessageProperties.receivedDelay，headers 里就看不到了）
        message.getMessageProperties().setHeader("x-delay", delay);
        // 3.添加自己的延迟标记（自定义头不会被转换器挪走，退回回调里能读到）
        message.getMessageProperties().setHeader(DELAYED_HEADER, true);
        return message;
    }
}
