package com.zhiwei.common.autoconfigure.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 错误队列的兜底消费者 —— 把「静默堆积」变成「可检索的日志」。
 * <p>
 * 背景：{@link MqConfig} 只声明了 {@code error.{服务名}.queue}，没有任何消费者。
 * 于是重试耗尽的消息会一直躺在队列里，除了 {@code rabbitmqctl list_queues} 的数字之外
 * 没有任何信号 —— 涨到磁盘满才会被发现。这里补上最后一段：读出来、记清楚、然后 ACK 掉。
 * </p>
 * <p>
 * <b>为什么实现 {@link MessageListener} 而不是用 {@code @RabbitListener}：</b>
 * 注解方式即使方法参数声明成 {@link Message}，Spring AMQP 的
 * {@code MessagingMessageListenerAdapter#onMessage} 仍会先调
 * {@code messageConverter.fromMessage()} 做一次转换（为了构造
 * {@code org.springframework.messaging.Message}）。而错误队列里的消息恰恰是「历史上某次
 * 消费失败的消息」，它的 {@code __TypeId__} header 可能指向一个已经不存在或改过包名的类
 * （实测踩到：旧消息的 typeId 是 {@code com.zhiwei.promotion.domain.dto.UserCouponDTO}，
 * 现在的类在 {@code ...domian.UserCouponDTO}）—— 转换一失败就抛
 * {@code MessageConversionException}，被 {@code ConditionalRejectingErrorHandler}
 * 判为 fatal 直接丢弃，连日志都留不下来，这个兜底类等于白加。
 * <br>
 * 直接实现 {@link MessageListener} 拿到的就是原始 {@link Message}，全程不碰 MessageConverter，
 * 无论消息体是什么、typeId 指向哪个类，都能原样打印出来。
 * </p>
 * <p>
 * <b>为什么必须自己吞掉所有异常：</b>
 * 一旦向上抛，容器会按 {@code defaultRequeueRejected} 决定是否重投；即便不重投，
 * 全局的 listener retry + {@code RepublishMessageRecoverer} 也可能把消息再发回
 * {@code error.{服务名}.queue} —— 也就是这个消费者自己监听的队列，形成死循环。
 * 所以整个方法体包在 try-catch 里，任何情况下都正常返回（即 ACK）。
 * </p>
 * <p>
 * 日志用固定前缀 {@code [MQ-ERROR-ALERT]}，便于在日志平台按关键字配告警规则；
 * 没有引入 webhook 之类的通知渠道，避免为一个兜底通道增加运维依赖。
 * </p>
 */
@Slf4j
public class ErrorMessageListener implements MessageListener {

    /**
     * 告警关键字，日志平台按这个配告警
     */
    private static final String ALERT_TAG = "[MQ-ERROR-ALERT]";

    /**
     * 消息体打印上限，避免大报文（比如整批订单）把日志打爆
     */
    private static final int MAX_BODY_LENGTH = 1000;

    @Override
    public void onMessage(Message message) {
        try {
            MessageProperties props = message.getMessageProperties();
            log.error("{} 消息重试耗尽，已进入错误队列需人工排查："
                            + "queue={}, messageId={}, originalExchange={}, originalRoutingKey={}, "
                            + "exception={}, deaths={}, body={}",
                    ALERT_TAG,
                    props.getConsumerQueue(),
                    props.getMessageId(),
                    // RepublishMessageRecoverer 转发时补的原始位置信息，取不到就是 null，不硬依赖
                    props.getHeader("x-original-exchange"),
                    props.getHeader("x-original-routingKey"),
                    props.getHeader("x-exception-message"),
                    describeDeaths(props.getHeader("x-death")),
                    truncate(message.getBody()));
        } catch (Exception e) {
            // 兜底通道自己出问题只能记一笔，绝不能往上抛 —— 抛出去可能被重投回本队列形成死循环
            log.error("{} 错误队列消息读取失败，已丢弃避免死循环", ALERT_TAG, e);
        }
    }

    /**
     * 把 x-death 里的「在哪死过、死了几次、什么原因」压成一行，比打印整个 List 好读
     */
    private String describeDeaths(Object deaths) {
        if (!(deaths instanceof List) || ((List<?>) deaths).isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : (List<?>) deaths) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> death = (Map<?, ?>) item;
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(death.get("queue"))
                    .append(" reason=").append(death.get("reason"))
                    .append(" count=").append(death.get("count"));
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private String truncate(byte[] body) {
        if (body == null || body.length == 0) {
            return "empty";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() <= MAX_BODY_LENGTH
                ? text
                : text.substring(0, MAX_BODY_LENGTH) + "...(truncated, total " + text.length() + " chars)";
    }
}
