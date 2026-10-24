package com.zhiwei.promotion.handler;

import com.zhiwei.common.autoconfigure.alert.WebhookAlertNotifier;
import com.zhiwei.common.autoconfigure.mq.ErrorMessageListener;
import com.zhiwei.common.utils.JsonUtils;
import com.zhiwei.promotion.constants.PromotionConstants;
import com.zhiwei.promotion.domian.UserCouponDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * promotion 专属的错误队列消费者，在 zw-common 默认兜底的基础上增加「存档」能力。
 * <p>
 * 背景：{@code MqConfig} 里的默认 {@link ErrorMessageListener} 只负责把 error 队列的消息
 * 读出来记告警日志，然后 ACK 掉 —— 消息从此消失，只有「发现失败」没有「恢复失败」。
 * 本类覆盖默认 bean（{@code @ConditionalOnMissingBean} 按类型 {@code ErrorMessageListener}
 * 判断，本类是其子类，故 promotion 上下文里默认 bean 不再创建、容器自动改用本类），
 * 在保留告警日志的同时把领券消息存进 Redis List {@code coupon:error:parked}，
 * 供 {@code couponErrorReplayJob} 手动触发重放。
 * </p>
 * <p>
 * 为什么存档而不是直接自动重投：消息进 error 队列意味着本地重试 3 次都失败，
 * 自动重投大概率还是失败，还可能形成「error 队列 → 重投 → 再失败」的循环。
 * 手动触发重放配合幂等键（userId:couponId + DB 回查）是安全的，失败原因排除后再放行。
 * </p>
 * <p>
 * 为什么继承而不是替换：{@code MqConfig.errorMessageContainer} 的注入参数类型是
 * {@code ErrorMessageListener}，用子类既满足注入，又能复用父类完整的告警日志逻辑
 * （x-death 解析、body 截断、吞异常防死循环），不重复造轮子。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromotionErrorListener extends ErrorMessageListener {

    private final StringRedisTemplate redisTemplate;
    private final WebhookAlertNotifier alertNotifier;

    @Override
    public void onMessage(Message message) {
        // 1. 先走父类：[MQ-ERROR-ALERT] 告警日志一条不少，且父类内部吞掉所有异常，
        //    保证走到这里时消息消费本身不会二次出事
        super.onMessage(message);
        // 2. 解析一次，供存档和 webhook 告警共用
        String body = null;
        UserCouponDTO uc = null;
        try {
            body = new String(message.getBody(), StandardCharsets.UTF_8);
            uc = JsonUtils.toBean(body, UserCouponDTO.class);
        } catch (Exception e) {
            // 解析失败不影响后续流程：存档跳过，告警降级为不带业务信息的版本
            log.warn("[MQ-ERROR-ARCHIVE] 消息体解析失败，跳过存档", e);
        }
        // 3. 存档：只有能解析成 UserCouponDTO 的领券消息才值得重放；
        //    其他消息（别的业务误入 error 队列、typeId 失配的历史消息）只告警不存档
        boolean archived = false;
        if (uc != null && uc.getUserId() != null && uc.getCouponId() != null) {
            try {
                redisTemplate.opsForList().leftPush(PromotionConstants.COUPON_ERROR_PARKED_KEY, body);
                archived = true;
            } catch (Exception e) {
                // 存档失败绝不能往上抛：抛出去容器会按 defaultRequeueRejected 处理，
                // 可能重投回本队列形成死循环。丢了存档还有告警兜底，损失可接受
                log.error("[MQ-ERROR-ARCHIVE] 领券消息存档失败，仅保留告警", e);
            }
        } else {
            log.warn("[MQ-ERROR-ARCHIVE] 消息不是有效的领券消息，不存档：{}", body);
        }
        // 4. webhook 推送：日志告警没人盯等于没有告警，把「进 error 队列」这件
        //    需要人介入的事推到群机器人上。同一 key 5 分钟节流，故障期不刷屏
        try {
            String detail = uc != null && uc.getUserId() != null && uc.getCouponId() != null
                    ? "couponId=" + uc.getCouponId() + ", userId=" + uc.getUserId()
                    : "消息体非领券格式";
            alertNotifier.notify("mq-error", "【领券链路】error 队列收到失败消息（" + detail + "），"
                    + (archived ? "已存档 coupon:error:parked，排除故障后请手动执行 couponErrorReplayJob 重放"
                                : "未存档，请根据 [MQ-ERROR-ALERT] 日志排查"));
        } catch (Exception e) {
            log.warn("[MQ-ERROR-ALERT] webhook 告警推送失败（不影响消费）", e);
        }
    }
}
