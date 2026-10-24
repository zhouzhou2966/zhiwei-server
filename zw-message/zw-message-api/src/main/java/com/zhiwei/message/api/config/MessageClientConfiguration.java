package com.zhiwei.message.api.config;


import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.message.api.client.AsyncSmsClient;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.zhiwei.common.constants.Constant.*;

@Configuration
@EnableFeignClients(basePackages = "com.zhiwei.message.api.client")
public class MessageClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RequestInterceptor requestIdInterceptor(){
        return template -> template
                .header(REQUEST_ID_HEADER, MDC.get(REQUEST_ID_HEADER))
                .header(REQUEST_FROM_HEADER, FEIGN_ORIGIN_NAME);
    }

    /**
     * 生产者侧声明短信 exchange。
     *
     * <p>为什么生产者也要声明：exchange 是生产者与消费者之间的契约，
     * 原先只有消费者（zw-message-service）的 @QueueBinding 会声明它。
     * 于是 dev 环境按需停掉 zw-message 后，exchange 根本不存在，
     * 生产者发布的消息全被 Broker nack —— 发码接口直接失败，
     * 连「Redis 存码 + 演示回显」这条不依赖消费者的链路都被拖累。
     *
     * <p>生产端启动时（RabbitAdmin 自动声明）保证契约存在：
     * 消费者不在线时消息滞留在 exchange（可配 TTL 或死信），
     * 消费者上线后由 sms.message.queue 继续消费，发布侧不受影响。
     *
     * <p>⚠️ 类型必须是 topic：虽然常量名叫 sms.direct，
     * 但 SmsMessageHandler 的 @Exchange 未指定 type，Spring 默认 topic。
     * 这里若按名字猜成 DirectExchange，消费者上线时会因类型不匹配
     * 触发 PRECONDITION_FAILED，channel 直接被 Broker 关闭。
     */
    @Bean
    public TopicExchange smsExchange() {
        return new TopicExchange(MqConstants.Exchange.SMS_EXCHANGE, true, false);
    }

    @Bean
    public AsyncSmsClient smsClient(RabbitMqHelper mqHelper){
        return new AsyncSmsClient(mqHelper);
    }
}