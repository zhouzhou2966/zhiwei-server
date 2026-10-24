package com.zhiwei.message.api.client;

import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.message.domain.dto.SmsInfoDTO;

public class AsyncSmsClient {
    private final RabbitMqHelper mqHelper;

    public AsyncSmsClient(RabbitMqHelper mqHelper) {
        this.mqHelper = mqHelper;
    }

/**
 * 基于 MQ 异步发送短信。
 *
 * <p>为什么失败必须抛异常而不是静默返回：mqHelper.send 的返回值代表
 * Broker 是否确认接收了这个消息。原先这里把返回值直接丢掉，
 * Broker 返回 nack / 连接异常时调用方以为短信已经发出去了，
 * 于是用户看着「发送成功」的提示干等一条永远不会到的短信，且无从排查。
 * 抛 BadRequestException 让调用方能感知并做补偿（如退还发码冷却）。
 *
 * @param smsInfoDTO 短信相关信息
 */
public void sendMessage(SmsInfoDTO smsInfoDTO){
    boolean confirmed = mqHelper.send(MqConstants.Exchange.SMS_EXCHANGE, MqConstants.Key.SMS_MESSAGE, smsInfoDTO);
    if (!confirmed) {
        throw new BadRequestException("短信消息未被 Broker 确认接收");
    }
}
}
