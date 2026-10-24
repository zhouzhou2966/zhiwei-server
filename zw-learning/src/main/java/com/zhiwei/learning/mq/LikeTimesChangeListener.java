package com.zhiwei.learning.mq;

import com.zhiwei.api.dto.remark.LikeTimesDTO;
import com.zhiwei.learning.domian.po.InteractionReply;
import com.zhiwei.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.zhiwei.common.constants.MqConstants.Exchange.LIKE_RECORD_EXCHANGE;
import static com.zhiwei.common.constants.MqConstants.Key.QA_LIKED_TIMES_KEY;


@RequiredArgsConstructor
@Slf4j
@Component
public class LikeTimesChangeListener {
    private final IInteractionReplyService replyService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "qa.liked.times.queue", durable = "true"),
            exchange = @Exchange(name = LIKE_RECORD_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = QA_LIKED_TIMES_KEY
    ))
    public void LikeTimesChange(List<LikeTimesDTO> likeTimesDTOS) {
        log.info("监听到回答或评论的点赞数发生改变");
        List<InteractionReply> replies = new ArrayList<>(likeTimesDTOS.size());
        for (LikeTimesDTO dto : likeTimesDTOS) {
            InteractionReply reply = new InteractionReply();
            reply.setId(dto.getBizId());
            reply.setLikedTimes(dto.getLikeTimes());
            replies.add(reply);
        }
        replyService.updateBatchById(replies);

    }
}
