package com.zhiwei.learning.mq;

import com.zhiwei.api.client.course.CourseClient;
import com.zhiwei.api.dto.trade.OrderBasicDTO;
import com.zhiwei.common.constants.MqConstants;

import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LessonChangeListener {
    private final ILearningLessonService learningLessonService;


/**
 * RabbitMQ消息监听器，用于处理用户课程订单支付成功的消息
 * 监听队列名为"learning.lesson.pay.queue"的TOPIC类型交换机消息
 * 当用户完成课程订单支付后，自动将该课程添加到用户的课表中
 */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "learning.lesson.pay.queue", durable = "true"),  // 持久化队列
            exchange = @Exchange(name = MqConstants.Exchange.ORDER_EXCHANGE, type = ExchangeTypes.TOPIC),  // TOPIC类型交换机
            key = MqConstants.Key.ORDER_PAY_KEY  // 路由键
    ))
    public void listenLessonPayMessage(OrderBasicDTO order) {
        //1. 校验订单数据是否为空
        if (order == null || order.getUserId() == null || CollUtils.isEmpty(order.getCourseIds())) {
            // 订单数据为空，直接返回
            log.error("接收到的MQ消息有误，订单数据为空");
            return;

        }
        //2. 调用服务层添加用户课程
        log.debug("监听到用户{}的订单{}，需要添加课程{}到课表", order.getUserId(), order.getOrderId(), order.getCourseIds());
        learningLessonService.addUserLessons(order.getUserId(), order.getCourseIds());
    }


/**
 * 监听课程取消消息的消费者方法
 * 使用RabbitMQ的@RabbitListener注解来监听指定队列的消息
 *
 * @param order 包含用户ID和课程ID的订单信息对象
 */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "learning.lesson.cancel.queue", durable = "true"),  // 定义队列，名称为learning.lesson.cancel.queue，持久化
            exchange = @Exchange(name = MqConstants.Exchange.PAY_EXCHANGE, type = ExchangeTypes.TOPIC),  // 定义交换机，使用主题类型交换机
            key = MqConstants.Key.REFUND_CHANGE  // 路由键，用于匹配消息
    ))
    public void listenLessonCancelMessage(OrderBasicDTO order) {
        //1. 校验订单数据是否为空
        if (order == null || order.getUserId() == null || CollUtils.isEmpty(order.getCourseIds())) {
            // 订单数据为空，直接返回
            log.error("接收到的MQ消息有误，订单数据为空");
            return;
        }
    // 调用服务层方法，删除用户与课程的关系
        learningLessonService.deleteMyLessons(order.getUserId(),order.getCourseIds());
    }
}
