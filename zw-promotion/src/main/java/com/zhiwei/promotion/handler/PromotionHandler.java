package com.zhiwei.promotion.handler;

import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.promotion.domian.UserCouponDTO;
import com.zhiwei.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.zhiwei.common.constants.MqConstants.Exchange.PROMOTION_EXCHANGE;
import static com.zhiwei.common.constants.MqConstants.Key.COUPON_RECEIVE_KEY;
import static com.zhiwei.promotion.constants.PromotionConstants.COUPON_ROLLBACK_KEY;

@Component
@RequiredArgsConstructor
@Slf4j
public class PromotionHandler {

    /**
     * 系统异常（DB 超时、连接池耗尽等）的本地重试次数。
     * 业务校验失败不重试 —— 券不存在/已发完/超限，重试多少次结果都一样。
     */
    private static final int MAX_RETRY = 3;

    /**
     * Redis 回滚的重试次数，三次都失败才落补偿集合
     */
    private static final int ROLLBACK_RETRY = 3;

    private final IUserCouponService userCouponService;
    private final RabbitMqHelper rabbitMqHelper;

    /**
     * 当前服务名，用来拼错误队列的 routing key：{@code error.{服务名}}
     */
    @Value("${spring.application.name}")
    private String appName;

    /**
     * 监听优惠券领取消息的消费者方法
     * <p>
     * 绑定到 <b>coupon.receive.queue</b> 队列，监听用户领取优惠券的事件。
     * 收到消息后调用 {@link IUserCouponService#checkAndCreateUserCoupon} 执行核心业务逻辑，
     * 包括校验、发放、持久化等步骤。
     * </p>
     * <p>
     * <b>异常处理与回滚机制：</b><br>
     * 重试由本方法自己完成，不依赖框架的 listener retry，原因有两个：
     * ① 回滚 Redis 是业务动作，需要拿到 {@link UserCouponDTO}，而框架的
     * {@code MessageRecoverer} 只能看到 {@code Message}；
     * ② 方法内 catch 了所有异常，框架 retry 本来也不会触发（见 bootstrap.yml 的说明）。
     * </p>
     * <ul>
     *   <li><b>业务异常</b>（{@link BadRequestException} / {@link BizIllegalException}）：
     *       券不存在、已发完、超限，重试无意义。回滚 Redis 后 ACK，<b>不进错误队列</b> ——
     *       这类失败是正常业务结果，扔进去只会把队列变成垃圾场。</li>
     *   <li><b>系统异常</b>（DB 超时、连接池耗尽等）：本地重试 {@value #MAX_RETRY} 次，
     *       仍失败则<b>先回滚 Redis，再转发到 error.promotion-service.queue</b>，保留现场供排查。</li>
     * </ul>
     * <p>
     * 回滚本身也可能失败（Redis 抖动），此时落进 Redis 待补偿集合，
     * 由 {@code couponRollbackRetryJob} 定时重试，避免库存永久泄漏。
     * </p>
     *
     * @param uc 用户优惠券 DTO，包含 codeId、couponId、userId 等关键信息
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "coupon.receive.queue", durable = "true"),
            exchange = @Exchange(value = PROMOTION_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = COUPON_RECEIVE_KEY
    ))
    public void listenCouponReceiveMessage(UserCouponDTO uc, Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        int attempts = 0;
        while (true) {
            try {
                userCouponService.checkAndCreateUserCoupon(uc, messageId);
                return;
            } catch (BadRequestException | BizIllegalException e) {
                log.warn("领券业务校验失败，回滚 Redis：couponId={}, userId={}, reason={}",
                        uc.getCouponId(), uc.getUserId(), e.getMessage());
                rollbackWithRetry(uc);
                return;
            } catch (Exception e) {
                attempts++;
                if (attempts >= MAX_RETRY) {
                    log.error("领券落库失败，已重试 {} 次，回滚 Redis 后转错误队列：couponId={}, userId={}",
                            MAX_RETRY, uc.getCouponId(), uc.getUserId(), e);
                    // 顺序不能反：先回滚是修数据（防库存泄漏），后转发是留现场（供排查）。
                    // 反过来会让 Redis 悬着一笔扣减，而错误队列没有消费者，没人会自动补回来。
                    rollbackWithRetry(uc);
                    forwardToErrorQueue(uc);
                    return;
                }
                log.warn("领券落库异常，第 {}/{} 次重试：couponId={}, userId={}",
                        attempts, MAX_RETRY, uc.getCouponId(), uc.getUserId(), e);
                sleepQuietly(200L * attempts);
            }
        }
    }

    /**
     * 回滚 Redis 中的领取记录，失败按退避重试；仍失败则落补偿集合等定时任务处理
     */
    private void rollbackWithRetry(UserCouponDTO uc) {
        for (int i = 1; i <= ROLLBACK_RETRY; i++) {
            try {
                userCouponService.rollbackCouponReceive(uc);
                return;
            } catch (Exception e) {
                log.error("回滚 Redis 失败，第 {}/{} 次：couponId={}, userId={}",
                        i, ROLLBACK_RETRY, uc.getCouponId(), uc.getUserId(), e);
                sleepQuietly(200L * i);
            }
        }
        log.error("回滚 Redis 连续 {} 次失败，转入补偿集合：couponId={}, userId={}",
                ROLLBACK_RETRY, uc.getCouponId(), uc.getUserId());
        userCouponService.addPendingRollback(uc);
    }

    /**
     * 把重试耗尽的失败消息转发到项目统一的错误队列
     * <p>
     * 复用 zw-common 的 {@code MqConfig} 已声明的 {@code error.topic} 交换机与
     * {@code error.promotion-service.queue} 队列，不另建一套死信队列 ——
     * 项目里已经有了，再造一个只会让排查时不知道该看哪个。
     * </p>
     * <p>
     * 转发的 payload 是 {@link UserCouponDTO}，会丢掉原消息的 messageId 与 x-death header。
     * 排查所需的信息（couponId / userId / codeId）都在 DTO 里，够用。
     * </p>
     */
    private void forwardToErrorQueue(UserCouponDTO uc) {
        try {
            boolean sent = rabbitMqHelper.send(
                    MqConstants.Exchange.ERROR_EXCHANGE,
                    MqConstants.Key.ERROR_KEY_PREFIX + appName,
                    uc);
            if (!sent) {
                log.error("失败消息转发错误队列返回 false，需人工处理：couponId={}, userId={}, codeId={}",
                        uc.getCouponId(), uc.getUserId(), uc.getCodeId());
            }
        } catch (Exception e) {
            // 转发也失败属于双重故障（MQ 与 Redis 同时不可用），只能靠日志 + 补偿集合，必须人工介入
            log.error("失败消息转发错误队列异常，需人工处理：couponId={}, userId={}, codeId={}",
                    uc.getCouponId(), uc.getUserId(), uc.getCodeId(), e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 兼容旧版本实例发出的回滚消息（滚动发布期间队列里可能还有存量）。
     * 新代码不再往这个队列发消息，改成直接调用 rollbackCouponReceive，少一次 MQ 往返。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "coupon.rollback.queue", durable = "true"),
            exchange = @Exchange(value = PROMOTION_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = COUPON_ROLLBACK_KEY
    ))
    public void listenCouponRollbackMessage(UserCouponDTO uc) {
        userCouponService.rollbackCouponReceive(uc);
    }
}
