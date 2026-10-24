package com.zhiwei.common.autoconfigure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.Set;

import static com.zhiwei.common.constants.Constant.REQUEST_ID_HEADER;
import static com.zhiwei.common.constants.MqConstants.Exchange.ERROR_EXCHANGE;
import static com.zhiwei.common.constants.MqConstants.Key.ERROR_KEY_PREFIX;
import static com.zhiwei.common.constants.MqConstants.Queue.ERROR_QUEUE_TEMPLATE;


@Slf4j
@Configuration
@ConditionalOnClass(value = {MessageConverter.class, AmqpTemplate.class})
public class MqConfig implements EnvironmentAware{

    /**
     * 延迟交换机名单：从这些交换机被退回属于 {@code x-delayed-message} 的固有行为，
     * 不是路由配错。新增延迟交换机时记得往这里加。
     */
    private static final Set<String> DELAYED_EXCHANGES =
            Collections.singleton(MqConstants.Exchange.TRADE_DELAY_EXCHANGE);

    private String defaultErrorRoutingKey;
    private String defaultErrorQueue;

    @Bean(name = "rabbitListenerContainerFactory")
    @ConditionalOnProperty(prefix = "spring.rabbitmq.listener", name = "type", havingValue = "simple",
            matchIfMissing = true)
    SimpleRabbitListenerContainerFactory simpleRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            ObjectProvider<ContainerCustomizer<SimpleMessageListenerContainer>> simpleContainerCustomizer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        simpleContainerCustomizer.ifUnique(factory::setContainerCustomizer);
        factory.setAfterReceivePostProcessors(message -> {
            Object header = message.getMessageProperties().getHeader(REQUEST_ID_HEADER);
            if(header != null) {
                MDC.put(REQUEST_ID_HEADER, header.toString());
            }
            return message;
        });
        return factory;
    }

    @Bean
    public MessageConverter messageConverter(ObjectMapper mapper){
        // 1.定义消息转换器
        Jackson2JsonMessageConverter jackson2JsonMessageConverter = new Jackson2JsonMessageConverter(mapper);
        // 2.配置自动创建消息id，用于识别不同消息
        jackson2JsonMessageConverter.setCreateMessageIds(true);
        return jackson2JsonMessageConverter;
    }

    /**
     * <h1>消息处理失败的重试策略</h1>
     * 本地重试失败后，消息投递到专门的失败交换机和失败消息队列：error.queue
     */
    @Bean
    @ConditionalOnClass(MessageRecoverer.class)
    @ConditionalOnMissingBean
    public MessageRecoverer republishMessageRecoverer(RabbitTemplate rabbitTemplate){
        // 消息处理失败后，发送到错误交换机：error.direct，RoutingKey默认是error.微服务名称
        return new RepublishMessageRecoverer(
                rabbitTemplate, ERROR_EXCHANGE, defaultErrorRoutingKey);
    }

    /**
     * rabbitmq发送工具
     *
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RabbitTemplate.class)
    public RabbitMqHelper rabbitMqHelper(RabbitTemplate rabbitTemplate){
        registerReturnsCallback(rabbitTemplate);
        return new RabbitMqHelper(rabbitTemplate);
    }

    /**
     * 给 RabbitTemplate 挂 ReturnsCallback，把「延迟消息的正常回退」和「真路由失败」分开。
     * <p>
     * 背景：Nacos 的 shared-mq.yaml 开了 {@code template.mandatory=true} +
     * {@code publisher-returns=true}，本意是让「exchange/routingKey 不匹配」的消息退回发送端
     * 而不是静默丢弃。但 {@code x-delayed-message} 类型的交换机在消息延迟期内并不持有队列路由，
     * broker 会按「无法路由」回一条 basic.return（实测 code=312 NO_ROUTE），而消息到期后
     * 仍会正常投递到队列。于是每发一条延迟消息，日志里就多一行
     * {@code WARN RabbitTemplate: Returned message but no callback available} —— 纯噪音，
     * 还会把真正的路由失败淹掉。
     * </p>
     * <p>
     * 分流规则见 {@link #isDelayedMessage(ReturnedMessage)}：延迟消息 DEBUG 记录，
     * 其余 ERROR 打 {@code [MQ-ROUTE-FAIL]} 前缀，便于日志平台单独告警。
     * </p>
     * <p>
     * 之所以不单独注册一个 bean 来定制模板：Spring Boot 2.7 还没有
     * {@code RabbitTemplateCustomizer}（3.x 才有），而重新定义 RabbitTemplate 会顶掉
     * Boot 自己的配置（converter、confirm/return 回调、连接工厂）。挂在 Helper 的创建点
     * 最省事，且 Helper 与模板是一一对应的。
     * </p>
     */
    private void registerReturnsCallback(RabbitTemplate template) {
        template.setReturnsCallback(returned -> {
            if (isDelayedMessage(returned)) {
                log.debug("延迟消息被 broker 回退（x-delayed-message 的固有行为，到期后仍会投递），"
                                + "exchange：{}，routingKey：{}",
                        returned.getExchange(), returned.getRoutingKey());
                return;
            }
            log.error("[MQ-ROUTE-FAIL] 消息无法路由已被退回，检查 exchange/routingKey 与队列绑定！"
                            + "exchange：{}，routingKey：{}，replyCode：{}，replyText：{}，message：{}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText(), returned.getMessage());
        });
    }

    /**
     * 判断被退回的消息是不是延迟消息。
     * <p>
     * ⚠️ 不能用 {@code getHeader("x-delay")} 判断。2026-10-21 实测：
     * AMQP 层的退回消息 headers 里 {@code x-delay} 是**存在**的（用原生 amqp-client 探针验证过），
     * 但 Spring 的 {@code DefaultMessagePropertiesConverter.fromMessage} 会把它从 headers
     * **挪进 {@code MessageProperties.receivedDelay} 字段**，所以走 Spring 的
     * {@code ReturnsCallback} 时 {@code getHeader("x-delay")} 恒为 null ——
     * 用它判断等于恒为 false，所有延迟消息都被误报成「真路由失败」。
     * </p>
     * <p>
     * 所以分三层，任一命中即认为是延迟消息：
     * <ol>
     *   <li>{@link MessageProperties#getReceivedDelay()}：Spring 官方字段，最可靠；</li>
     *   <li>{@link DelayedMessageProcessor#DELAYED_HEADER}：发送侧自己打的标记，语义是
     *       「发送方声称这是延迟消息」，和 1 互相印证；</li>
     *   <li>交换机名在 {@link #DELAYED_EXCHANGES} 里：兜底覆盖「标记头上线之前发出的存量消息」，
     *       以及将来有人在别处直接发延迟消息、没走 {@code sendDelayMessage} 的情况。</li>
     * </ol>
     * </p>
     * <p>
     * ⚠️ 不能只靠第 1 条：反编译 {@code DefaultMessagePropertiesConverter} 可见，它只在
     * {@code x-delay} 的值是 {@code Integer} 时才调 {@code setReceivedDelay}；如果是
     * {@code Long}（AMQP 大整数反序列化的常见结果），这个字段就一直是 null。所以必须有
     * 第 2、3 层兜底。
     * </p>
     */
    private boolean isDelayedMessage(ReturnedMessage returned) {
        MessageProperties props = returned.getMessage().getMessageProperties();
        // Spring AMQP 3.x 把 getReceivedDelay()（Integer）换成了 getReceivedDelayLong()（Long），
        // 判断语义不变：字段非空就说明这条消息带着 x-delay 被退回。
        if (props.getReceivedDelayLong() != null) {
            return true;
        }
        if (props.getHeader(DelayedMessageProcessor.DELAYED_HEADER) != null) {
            return true;
        }
        return DELAYED_EXCHANGES.contains(returned.getExchange());
    }

    /**
     * 专门接收处理失败的消息
     */
    @Bean
    public DirectExchange errorMessageExchange(){
        return new DirectExchange(ERROR_EXCHANGE);
    }

    @Bean
    public Queue errorQueue(){
        return new Queue(defaultErrorQueue, true);
    }

    @Bean
    public Binding errorBinding(Queue errorQueue, DirectExchange errorMessageExchange){
        return BindingBuilder.bind(errorQueue).to(errorMessageExchange).with(defaultErrorRoutingKey);
    }

    /**
     * 错误队列的消费者容器 —— 只负责把消息读出来记日志，不做重放。
     * <p>
     * 没有它的时候，{@code error.{服务名}.queue} 是只进不出的：重试耗尽的消息永远躺在里面，
     * 除了队列长度之外没有任何可见性。加上之后，失败消息至少会以 {@code [MQ-ERROR-ALERT]}
     * 前缀落到日志里，可以被日志平台检索和告警。
     * </p>
     * <p>
     * 这里刻意不用 {@code @RabbitListener}：注解方式会先过一遍 {@code MessageConverter}，
     * 而错误队列里的消息 typeId 可能指向已改包名/已删除的类，转换失败会被判 fatal 直接丢弃，
     * 连日志都留不下来。直接用 {@link SimpleMessageListenerContainer} + {@link ErrorMessageListener}
     * 拿到原始 Message，绕开转换。
     * </p>
     * <p>
     * 单线程消费 + {@code defaultRequeueRejected=false}：这是兜底通道，不需要吞吐；
     * 万一监听器自身出问题，宁可丢弃也不要重投回同一个队列形成死循环。
     * </p>
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ErrorMessageListener.class)
    public SimpleMessageListenerContainer errorMessageContainer(ConnectionFactory connectionFactory,
                                                               ErrorMessageListener errorMessageListener){
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(defaultErrorQueue);
        container.setMessageListener(errorMessageListener);
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setDefaultRequeueRejected(false);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        return container;
    }

    @Bean
    @ConditionalOnMissingBean
    public ErrorMessageListener errorMessageListener(){
        return new ErrorMessageListener();
    }

    @Override
    public void setEnvironment(Environment environment) {
        String appName = environment.getProperty("spring.application.name");
        this.defaultErrorRoutingKey = ERROR_KEY_PREFIX + appName;
        this.defaultErrorQueue = StringUtils.format(ERROR_QUEUE_TEMPLATE, appName);
    }
}
