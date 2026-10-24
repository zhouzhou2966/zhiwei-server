package com.zhiwei.message.handler;

import com.zhiwei.api.dto.sms.SmsInfoDTO;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.message.service.ISmsService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SmsMessageHandler {

    private final ISmsService smsService;

    /**
     * ⚠️ {@code type} 必须显式写成 topic，不能省。
     * <p>
     * 这个交换机在生产者侧（{@code MessageClientConfiguration#smsExchange}）是以
     * {@code TopicExchange} 声明的，而 {@code @Exchange} 的 {@code type} 默认值是
     * {@code direct}。两边类型不一致时，先启动的一方把交换机建出来，后启动的一方
     * 声明时会收到 {@code 406 PRECONDITION_FAILED - inequivalent arg 'type'}，
     * 通道被关闭，连带后面所有队列/绑定声明全部失败——表现就是「短信发不出去、
     * 站内信监听器起不来」，但服务本身启动成功、日志里只有一串 channel error。
     * </p>
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "sms.message.queue", durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.SMS_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.SMS_MESSAGE
    ))
    public void listenSmsMessage(SmsInfoDTO smsInfoDTO){
        smsService.sendMessage(smsInfoDTO);
    }
}
