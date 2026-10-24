package com.zhiwei.learning.mq;

import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.learning.domian.enums.PointsRecordType;
import com.zhiwei.learning.message.SignInMessage;
import com.zhiwei.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.zhiwei.common.constants.MqConstants.Exchange.LEARNING_EXCHANGE;
import static com.zhiwei.common.constants.MqConstants.Key.WRITE_REPLY;

@Component
@RequiredArgsConstructor
@Slf4j
public class LearningPointsListener {
    private final IPointsRecordService pointsRecordService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "qa.points.queue", durable = "true"),
            exchange = @Exchange(value = LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = WRITE_REPLY
    ))
    public void listenWriteReplyMessage(Long userId) {
        log.info( "监听到MQ消息,用户{}发布回复，增加积分", userId);
        pointsRecordService.addPointsRecord(userId, 5, PointsRecordType.QA);

    }


    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "sign.points.queue", durable = "true"),
            exchange = @Exchange(value = LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.SIGN_IN
    ))
    public void listenSignInMessage(SignInMessage message) {
        log.info( "监听到MQ消息,用户{}签到，增加积分", message.getUserId());
        pointsRecordService.addPointsRecord(message.getUserId(), message.getPoints(), PointsRecordType.SIGN);

    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "learn.points.queue", durable = "true"),
            exchange = @Exchange(value = LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.LEARN_SECTION
    ))
     public void listenLearnSectionMessage(Long userId) {
        log.info( "监听到MQ消息,用户{}学习小节，增加积分", userId);
        pointsRecordService.addPointsRecord(userId, 5, PointsRecordType.LEARNING);

    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "learn.points.queue", durable = "true"),
            exchange = @Exchange(value = LEARNING_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.WRITE_NOTE
    ))
    public void listenWriteNoteMessage(Long userId) {
        log.info( "监听到MQ消息,用户{}发布笔记，增加积分", userId);
        pointsRecordService.addPointsRecord(userId, 5, PointsRecordType.NOTE);

    }
}
