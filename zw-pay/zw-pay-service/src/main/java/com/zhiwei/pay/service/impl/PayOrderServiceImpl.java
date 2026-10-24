package com.zhiwei.pay.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.autoconfigure.redisson.annotations.Lock;
import com.zhiwei.common.autoconfigure.redisson.enums.LockStrategy;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.pay.constants.NotifyStatus;
import com.zhiwei.pay.domain.po.PayOrder;
import com.zhiwei.pay.mapper.PayOrderMapper;
import com.zhiwei.pay.sdk.constants.PayConstants;
import com.zhiwei.pay.sdk.constants.PayErrorInfo;
import com.zhiwei.pay.sdk.dto.PayApplyDTO;
import com.zhiwei.pay.sdk.dto.PayResultDTO;
import com.zhiwei.pay.service.IPayOrderService;
import com.zhiwei.pay.third.CommonPayProperties;
import com.zhiwei.pay.third.IPayService;
import com.zhiwei.pay.third.model.PayStatus;
import com.zhiwei.pay.third.model.PayStatusResponse;
import com.zhiwei.pay.third.model.PrepayResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.zhiwei.pay.sdk.constants.PayErrorInfo.*;

/**
 * <p>
 * 支付订单 服务实现类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderServiceImpl extends ServiceImpl<PayOrderMapper, PayOrder> implements IPayOrderService {

    @Resource
    private Map<String, IPayService> payServiceChannels;
    private final RabbitMqHelper rabbitMqHelper;
    private final CommonPayProperties payProperties;

    /**
     * 同一支付单向第三方（支付宝/微信）真实查单的最小间隔，单位毫秒。
     * <p>
     * 查单这条路径现在有三个来源同时在打：前端支付页 5s 轮询、trade 下单后投递的延迟查单消息
     * （5s/15s/30s/60s/120s）、XXL-JOB 补偿任务。若不节流，一个用户开着支付页就等于每 5 秒
     * 打一次支付宝网关，支付宝对 query 接口有频率限制，多人同时付款时很容易被限流，
     * 表现为「查单超时 → 状态迟迟不更新」。3s 间隔刚好让每次前端轮询都能真查一次，
     * 同时把延迟消息和补偿任务的重复查询吸收掉。
     */
    private static final long QUERY_CHANNEL_MIN_INTERVAL_MILLIS = 3000L;

    /** 支付单 id → 上次真实查单的时间戳。单实例内存节流，多实例部署时各实例独立计数。 */
    private final Map<Long, Long> lastChannelQueryAt = new ConcurrentHashMap<>();

    /**
     * 申请支付单。
     *
     * 锁参数说明：原来写的是 leaseTime = 3 + autoUnlock = false，两个问题：
     * ① 不主动释放、只等 3 秒过期 —— 用户双击「支付」时第二个请求 waitTime(默认 1s) 内拿不到锁，
     *    直接 500「请求超时」，而正确行为应该是等第一笔做完后走幂等分支、返回同一个二维码；
     * ② 业务执行超过 3 秒（调支付宝网络慢时很常见）锁会提前自动释放，并发保护形同虚设。
     * 现在改成 autoUnlock = true + waitTime = 5，leaseTime 用默认 -1（Redisson watchdog 自动续期）。
     */
    @Override
    @Lock(name = PayConstants.RedisKeyFormatter.PAY_APPLY, waitTime = 5, autoUnlock = true)
    public String applyPayOrder(PayApplyDTO payApplyDTO) {
        log.debug("准备创建支付单，业务订单号：{}", payApplyDTO.getBizOrderNo());
        // 1.选择支付渠道
        IPayService payService = payServiceChannels.get(payApplyDTO.getPayChannelCode());
        if (payService == null) {
            log.error("用户选择的支付渠道不存在，业务单号：{}", payApplyDTO.getBizOrderNo());
            throw new BadRequestException(INVALID_PAY_CHANNEL);
        }

        // 2.幂等性校验
        PayOrder payOrder = checkIdempotent(payApplyDTO);
        if (StringUtils.isNotBlank(payOrder.getQrCodeUrl())) {
            log.debug("支付链接已经存在，不再重新创建，直接返回");
            return payOrder.getQrCodeUrl();
        }

        // 3.需要生成新支付链接，调用第三方，完成支付单下单
        PrepayResponse prepayResponse = payService.createPrepayOrder(
                payApplyDTO.getOrderInfo(), payOrder.getPayOrderNo().toString(), payOrder.getAmount());

        // 4.更新支付链接等信息到数据库
        updatePayResult2DB(prepayResponse, payOrder.getId());

        if (!prepayResponse.isSuccess()) {
            // 三个字段都要打：code 是渠道错误码，msg 是渠道文案，detail 常常为 null（这次排查就栽在这），
            // 只打 detail 等于什么都没打
            log.error("预创建支付单失败，渠道：{}，错误码：{}，错误信息：{}，详情：{}",
                    payApplyDTO.getPayChannelCode(), prepayResponse.getCode(),
                    prepayResponse.getMsg(), prepayResponse.getDetail());
            throw new BizIllegalException(PayErrorInfo.CREATE_PAY_ORDER_FAILED);
        }
        // 5.返回支付链接
        log.debug("支付单生成成功，返回支付链接：{}", prepayResponse.getPayUrl());
        return prepayResponse.getPayUrl();
    }

    private void updatePayResult2DB(PrepayResponse prepayResponse, Long payOrderId) {
        try {
            boolean success = prepayResponse.isSuccess();
            lambdaUpdate()
                    .set(success, PayOrder::getQrCodeUrl, prepayResponse.getPayUrl())
                    .set(success, PayOrder::getStatus, PayStatus.WAIT_BUYER_PAY.getValue())
                    // 成功时必须清空上一次失败留下的错误信息：同一个业务单号换渠道重试成功后，
                    // 旧的 result_code 会一直残留（换渠道那条路径用 updateById，null 字段被忽略，清不掉），
                    // 订单超时关闭时就会被当成真实失败原因展示给用户
                    .set(success, PayOrder::getResultCode, "")
                    .set(success, PayOrder::getResultMsg, "")
                    .set(!success, PayOrder::getResultCode, prepayResponse.getCode())
                    .set(!success, PayOrder::getResultMsg, prepayResponse.getMsg())
                    .eq(PayOrder::getId, payOrderId)
                    .update();
        } catch (Exception e) {
            log.error("更新支付单结果到数据时发生异常", e);
        }
    }

    private PayOrder buildPayOrder(PayApplyDTO payApplyDTO) {
        // 1.数据转换
        PayOrder payOrder = BeanUtils.toBean(payApplyDTO, PayOrder.class);
        // 2.初始化数据
        payOrder.setNotifyTimes(0);
        payOrder.setNotifyStatus(NotifyStatus.UN_CALL.getValue());
        payOrder.setPayOverTime(LocalDateTime.now().plusMinutes(payProperties.getOrderTTLMinutes()));
        payOrder.setStatus(PayStatus.NOT_COMMIT.getValue());
        return payOrder;
    }

    private PayOrder checkIdempotent(PayApplyDTO payApplyDTO) {
        // 1.首先查询支付订单
        PayOrder oldOrder = queryByBizOrderNo(payApplyDTO.getBizOrderNo());
        // 2.判断是否存在
        if (oldOrder == null) {
            // 不存在支付单，说明是第一次，写入新的支付单并返回
            PayOrder payOrder = buildPayOrder(payApplyDTO);
            payOrder.setPayOrderNo(IdWorker.getId());
            save(payOrder);
            return payOrder;
        }
        // 3.旧单已经存在，判断是否支付成功
        if (PayStatus.TRADE_SUCCESS.equalsValue(oldOrder.getStatus())) {
            // 已经支付成功，抛出异常
            throw new BizIllegalException(PAY_ORDER_ALREADY_PAY_CODE, PAY_ORDER_ALREADY_PAY);
        }
        // 3.1.同一业务单号的金额必须一致：金额变了还复用旧单，等于用旧金额的二维码收新订单的钱，
        // 用户实际支付金额与业务侧订单金额对不上，而且不会有任何报错
        if (!oldOrder.getAmount().equals(payApplyDTO.getAmount())) {
            log.error("同一业务订单号的支付金额不一致，业务单号：{}，已有金额：{}，本次金额：{}",
                    payApplyDTO.getBizOrderNo(), oldOrder.getAmount(), payApplyDTO.getAmount());
            throw new BizIllegalException(PAY_ORDER_AMOUNT_MISMATCH);
        }
        // 4.旧单已经存在，判断是否已经关闭
        if (PayStatus.TRADE_CLOSED.equalsValue(oldOrder.getStatus())) {
            // 已经关闭，抛出异常
            throw new BizIllegalException(PAY_ORDER_ALREADY_CLOSE_CODE, PAY_ORDER_ALREADY_CLOSE);
        }
        // 5.旧单已经存在，判断支付渠道是否一致
        if (!StringUtils.equals(oldOrder.getPayChannelCode(), payApplyDTO.getPayChannelCode())) {
            // 支付渠道不一致，需要重置数据，然后重新申请支付单
            PayOrder payOrder = buildPayOrder(payApplyDTO);
            payOrder.setId(oldOrder.getId());
            payOrder.setQrCodeUrl("");
            updateById(payOrder);
            payOrder.setPayOrderNo(oldOrder.getPayOrderNo());
            return payOrder;
        }
        // 6.旧单已经存在，且可能是未支付或未提交，且支付渠道一致，直接返回旧数据
        return oldOrder;
    }

    @Override
    public PayOrder queryByBizOrderNo(Long bizOrderNo) {
        return lambdaQuery()
                .eq(PayOrder::getBizOrderNo, bizOrderNo)
                .one();
    }

    @Override
    public PayResultDTO queryPayResult(Long bizOrderNo) {
        // 1.查询支付单
        PayOrder payOrder = queryByBizOrderNo(bizOrderNo);
        if (payOrder == null) {
            // 支付单还没建出来。trade 建完业务订单和建支付单之间有时间差，
            // 而支付页进来就开始 5s 一轮轮询，这一轮必然打在「支付单不存在」上。
            //
            // 这里绝对不能抛异常，原因有两个，第二个是会丢消息的：
            // ① trade 的 OrderServiceImpl#syncFromPayCenter 每次轮询都会调本接口，
            //    抛异常会让它每 5 秒打一条带完整堆栈的 WARN，把真正的告警淹掉；
            // ② ★ trade 的 PayServiceImpl#queryPayResult（延迟查单消息的消费者）调本接口
            //    **没有 try-catch**，异常会冒泡到 MQ 监听器。Spring AMQP 默认
            //    defaultRequeueRejected=true，消息会被无限重投 —— 只要延迟消息比支付单先到，
            //    这条消息就再也消费不掉了，该实例的监听线程被反复占用。
            //
            // 语义上也说得通：支付单还没建 = 用户还没开始付，就是「支付中」。
            return PayResultDTO.builder()
                    .status(PayResultDTO.PAYING)
                    .build();
        }
        // 2.判断支付状态
        if (payOrder.success()) {
            // 2.1.支付成功
            // status 必须显式给出：PayResultDTO 的 status 默认 0，而调用方（zw-trade 的
            // PayServiceImpl#queryPayResult）用 `PayResultDTO.SUCCESS != status` 判断是否已支付，
            // 漏掉这行会让「回调丢失、靠轮询兜底」的场景永远判成未支付 —— 用户付了钱业务侧看不到
            return PayResultDTO.builder()
                    .status(PayResultDTO.SUCCESS)
                    // bizOrderId 必须给：调用方 zw-trade 的 OrderServiceImpl#handlePaySuccess
                    // 第一行就是 getById(payResult.getBizOrderId())，缺了它拿到 null 会静默 return，
                    // 表现为「pay 侧已支付、trade 侧永远停在待支付」，且延迟查单窗口用尽后无法自愈
                    .bizOrderId(payOrder.getBizOrderNo())
                    .payOrderNo(payOrder.getPayOrderNo())
                    .successTime(payOrder.getPaySuccessTime())
                    .payChannel(payOrder.getPayChannelCode())
                    .build();
        }
        // 2.2.未支付
        if (payOrder.notCommit() || payOrder.waitBuyerPay()) {
            // 先真去第三方查一次，再决定返回什么。
            // 原来这里直接返回「待支付」，看起来没问题，实际让整条兜底链路空转：
            // trade 下单后会发 3s/5s/15s/30s/60s/120s 六次延迟查单消息，每次最终都调到这里，
            // 而这里只读本地表；本地表又只有 XXL-JOB 补偿任务（120s 一轮）才会更新。
            // 结果就是「回调收不到 = 用户付完钱最长要等 2 分钟」，实测 19:06:01 付款、19:06:40 才变已支付。
            if (!allowQueryChannel(payOrder.getId())) {
                // 3 秒内已经真查过一次，直接按本地状态返回。
                // 这里不可能误判「已支付」：上面的 success() 分支已经处理过本地成功的情况，
                // 走到这里说明库里确实是待支付，最多让调用方多等一个节流周期。
                return PayResultDTO.builder()
                        .status(PayStatus.WAIT_BUYER_PAY.getValue())
                        .build();
            }
            PayStatusResponse response = queryFromChannel(payOrder);
            if (response != null && PayStatus.TRADE_SUCCESS.equalsValue(response.getPayStatus())) {
                // 条件更新：只有把状态从「待支付」真正改成「成功」的那一次才算数，
                // 并发查单 / 补偿任务同时命中时只有一个能成功，保证成功通知只发一次
                if (updatePayStatus2DB(response, payOrder.getId(), payOrder.getStatus())) {
                    // 注意：这里不发 MQ。
                    // 调用方（trade 的延迟查单消费者）拿到 SUCCESS 后会自己走 handlePaySuccess，
                    // 若这里再发一条 pay.success，同一笔支付会被处理两次（重复报名）。
                    // 发 MQ 只保留在补偿任务那条路径上，作为「trade 侧没来问」时的兜底。
                    return PayResultDTO.builder()
                            .status(PayResultDTO.SUCCESS)
                            .bizOrderId(payOrder.getBizOrderNo())
                            .payOrderNo(payOrder.getPayOrderNo())
                            .successTime(response.getSuccessTime())
                            .payChannel(payOrder.getPayChannelCode())
                            .build();
                }
                // 状态已被其它路径改过，按库里最新值返回，避免重复处理
                PayOrder latest = getById(payOrder.getId());
                if (latest != null && latest.success()) {
                    return PayResultDTO.builder()
                            .status(PayResultDTO.SUCCESS)
                            .bizOrderId(latest.getBizOrderNo())
                            .payOrderNo(latest.getPayOrderNo())
                            .successTime(latest.getPaySuccessTime())
                            .payChannel(latest.getPayChannelCode())
                            .build();
                }
            }
            return PayResultDTO.builder()
                    .status(PayStatus.WAIT_BUYER_PAY.getValue())
                    .build();
        }
        // 2.2.支付失败
        return PayResultDTO.builder()
                .status(PayStatus.TRADE_CLOSED.getValue())
                .msg(payOrder.getResultMsg())
                .build();
    }

    @Override
    public PayResultDTO closeByBizOrderNo(Long bizOrderNo) {
        // 1.查支付单
        PayOrder payOrder = queryByBizOrderNo(bizOrderNo);
        if (payOrder == null) {
            // 用户下单后从没点过「去支付」：pay 侧不存在任何可支付的二维码，
            // 也就没有「关掉渠道」这回事，业务侧可以安全关单。
            // 这里绝不能返回 PAYING —— 否则这类订单会永远关不掉，把扫表每轮的前 N 条全堵住，
            // 新产生的超时订单永远排不上（pay 侧 canCloseOnTimeout 的注释里踩过同样的坑）。
            return PayResultDTO.builder()
                    .status(PayResultDTO.FAILED)
                    .msg("支付单不存在")
                    .build();
        }
        // 2.已支付成功：绝不能关，把支付信息带回去让业务侧转成「已支付」
        if (payOrder.success()) {
            return buildSuccessResult(payOrder);
        }
        // 3.已关闭：幂等返回，业务侧可以关单
        if (payOrder.closed()) {
            return PayResultDTO.builder()
                    .status(PayResultDTO.FAILED)
                    .msg("支付单已关闭")
                    .build();
        }
        // 4.走已有的完整关单逻辑：先关渠道 → 关不掉就查单 → 已支付转成功 → 确认未支付才关本地。
        //    刻意复用私有 closeOrder 而不是另写一套：关单顺序错一步就是资损，两套逻辑迟早走偏
        closeOrder(payOrder.getId());
        // 5.closeOrder 是 void，它可能「关成功」「转成支付成功」「因渠道异常保持原状」三种结果，
        //    只能重新查库判断
        PayOrder latest = getById(payOrder.getId());
        if (latest == null) {
            return PayResultDTO.builder().status(PayResultDTO.FAILED).msg("支付单已不存在").build();
        }
        if (latest.success()) {
            return buildSuccessResult(latest);
        }
        if (latest.closed()) {
            return PayResultDTO.builder().status(PayResultDTO.FAILED).msg("支付单已关闭").build();
        }
        // 6.仍是待支付 = 渠道关不掉（关单报错 + 查单也异常）。
        //    此时用户手里的二维码还能付款，业务侧必须放弃本轮关单、等下一轮重试，否则就是资损
        return PayResultDTO.builder()
                .status(PayResultDTO.PAYING)
                .msg("支付单暂时无法关闭，稍后重试")
                .build();
    }

    /**
     * 组装「已支付」的返回结果。
     * <p>
     * bizOrderId / payOrderNo / successTime 三个字段一个都不能少：业务侧拿到后直接走 handlePaySuccess，
     * 缺 bizOrderId 会静默 return（表现为「pay 已支付、trade 永远待支付」），
     * 缺 successTime 会让 order.pay_time 落空。
     * </p>
     */
    private PayResultDTO buildSuccessResult(PayOrder payOrder) {
        return PayResultDTO.builder()
                .status(PayResultDTO.SUCCESS)
                .bizOrderId(payOrder.getBizOrderNo())
                .payOrderNo(payOrder.getPayOrderNo())
                .successTime(payOrder.getPaySuccessTime())
                .payChannel(payOrder.getPayChannelCode())
                .build();
    }

    @Override
    public PayOrder queryByPayOrderNo(Long payOrderNo) {
        return lambdaQuery().eq(PayOrder::getPayOrderNo, payOrderNo).one();
    }

    @Override
    public boolean markPayOrderSuccess(Long id, LocalDateTime successTime) {
        return lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.TRADE_SUCCESS.getValue())
                .set(PayOrder::getNotifyStatus, NotifyStatus.CALLING.getValue())
                .set(PayOrder::getPaySuccessTime, successTime)
                .eq(PayOrder::getId, id)
                // 支付状态的乐观锁判断
                .in(PayOrder::getStatus, PayStatus.NOT_COMMIT.getValue(), PayStatus.WAIT_BUYER_PAY.getValue())
                .update();
    }

    @Override
    public PageDTO<PayOrder> queryPayingOrderByPage(int shardIndex, int shardTotal, int size) {
        // 1.分页和排序条件（shardIndex 从 0 开始）
        Page<PayOrder> page = new Page<>(1, size);
        page.addOrder(  OrderItem.asc("id"));
        // 2.查询
        Page<PayOrder> result = lambdaQuery()
                .eq(PayOrder::getStatus, PayStatus.WAIT_BUYER_PAY.getValue())
                // 真正的分片：按 id 对分片总数取模。
                // 原来是把「分片号 + 1」当 pageNo 用，排序又是 id ASC——数据一增删页码就漂移，
                // 各分片之间会重复处理或漏处理，不是真正的分区
                .apply(shardTotal > 1, "MOD(id, {0}) = {1}", shardTotal, shardIndex)
                .page(page);
        return PageDTO.of(result);
    }

    @Override
    @Lock(name = PayConstants.RedisKeyFormatter.PAY_ORDER_CHECK_TASK, lockStrategy = LockStrategy.SKIP_AFTER_RETRY_TIMEOUT)
    public void checkPayOrder(PayOrder payOrder) {
        // 1.选择支付渠道
        IPayService payService = payServiceChannels.get(payOrder.getPayChannelCode());
        if (payService == null) {
            log.error("支付渠道不存在，业务单号：{}", payOrder.getBizOrderNo());
            // 渠道配置有问题时这笔单永远查不了状态，只能关闭，否则会一直占着补偿任务的扫描额度
            closeOrder(payOrder.getId());
            return;
        }
        // 2.先查支付状态，再判断是否超时关单 —— 这两步的顺序不能反。
        // 原实现是先判超时直接 closeOrder、根本没问过支付宝：用户在超时前最后一刻付款时，
        // 补偿任务 120s 后跑起来看到「已超时」就把支付单关掉，而钱其实已经收了，
        // trade 侧也再收不到任何通知 —— 这是纯粹的资损。
        // 微信官方《支付回调和查单实现指引》5.4 的要求就是：先查单，NOTPAY 才关单，SUCCESS 不关单。
        PayStatusResponse response;
        try {
            response = payService.queryPayOrderStatus(payOrder.getPayOrderNo().toString());
        } catch (Exception e) {
            // 查单失败时无法判断用户是否已付款，本轮什么都不做，等下一轮再查
            log.error("支付单{}查单失败，本轮跳过", payOrder.getPayOrderNo(), e);
            return;
        }
        // 2.1.查单失败 / 仍在支付中
        if (response == null || !response.isSuccess()
                || PayStatus.WAIT_BUYER_PAY.equalsValue(response.getPayStatus())) {
            // 只有「确认不可支付」且「已超时」才允许关单。
            // 查单异常时绝不能关：那等于在不知道用户付没付的情况下把钱退掉。
            if (payOrder.getPayOverTime().isBefore(LocalDateTime.now()) && canCloseOnTimeout(response)) {
                log.debug("支付单{}确认不可支付且已超时，关闭订单", payOrder.getPayOrderNo());
                closeOrder(payOrder.getId());
            }
            return;
        }
        // 2.2.支付状态没有变更，无需处理
        if (response.getPayStatus().equals(payOrder.getStatus())) {
            return;
        }
        // 2.3.状态变成成功或失败，落库。
        // 条件更新返回 false 说明状态已被「查单接口」先一步改掉并通知过了，
        // 这里必须直接结束，否则同一笔支付会触发两次「支付成功」，trade 侧会重复报名
        if (!updatePayStatus2DB(response, payOrder.getId(), payOrder.getStatus())) {
            log.debug("支付单{}状态已被其它路径更新，跳过本次通知", payOrder.getPayOrderNo());
            return;
        }
        // 2.4.支付成功需要发送MQ消息通知
        if (PayStatus.TRADE_SUCCESS.equalsValue(response.getPayStatus())) {
            sendPaySuccessMessage(response, payOrder);
        }
    }

    /**
     * 节流判断：同一支付单在 {@link #QUERY_CHANNEL_MIN_INTERVAL_MILLIS} 内只允许真实查单一次。
     * <p>
     * 用 {@code compute} 而不是「先 get 再 put」：前端轮询、延迟消息、补偿任务可能并发进入，
     * 两步操作会出现两个线程同时判定「可以查」，节流就失效了。compute 对同一个 key 是原子的。
     * </p>
     */
    private boolean allowQueryChannel(Long payOrderId) {
        // 极端情况下（大量历史支付单堆积）直接清空，宁可让节流失效一轮也不让内存无限增长
        if (lastChannelQueryAt.size() > 20000) {
            lastChannelQueryAt.clear();
        }
        long now = System.currentTimeMillis();
        boolean[] allowed = {false};
        lastChannelQueryAt.compute(payOrderId, (id, last) -> {
            if (last != null && now - last < QUERY_CHANNEL_MIN_INTERVAL_MILLIS) {
                return last;
            }
            allowed[0] = true;
            return now;
        });
        return allowed[0];
    }

    /**
     * 向第三方渠道真实查询一次支付状态。
     * 查单失败不能把异常抛给「查状态」这个读接口，否则 trade 侧延迟消息消费会失败，
     * 按未支付返回、等下一轮重试即可。
     */
    private PayStatusResponse queryFromChannel(PayOrder payOrder) {
        IPayService payService = payServiceChannels.get(payOrder.getPayChannelCode());
        if (payService == null) {
            log.error("支付渠道不存在，业务单号：{}", payOrder.getBizOrderNo());
            return null;
        }
        try {
            return payService.queryPayOrderStatus(payOrder.getPayOrderNo().toString());
        } catch (Exception e) {
            log.error("向第三方查询支付单状态失败，支付单号：{}", payOrder.getPayOrderNo(), e);
            return null;
        }
    }

    /**
     * 支付成功后通知业务方。只有「状态真正迁移到成功」的那一次才会走到这里，
     * 保证 trade 侧的报名逻辑不会被重复触发。
     */
    private void sendPaySuccessMessage(PayStatusResponse response, PayOrder payOrder) {
        rabbitMqHelper.send(
                MqConstants.Exchange.PAY_EXCHANGE,
                MqConstants.Key.PAY_SUCCESS,
                PayResultDTO.builder()
                        // status 必须显式给出（默认 0）。当前消费者不校验，但语义上这是一条
                        // 「支付成功」消息，留 0 等于自相矛盾；以后一旦有人按 status 分支就会踩坑
                        .status(PayResultDTO.SUCCESS)
                        .payOrderNo(payOrder.getPayOrderNo())
                        .bizOrderId(payOrder.getBizOrderNo())
                        .payChannel(payOrder.getPayChannelCode())
                        .successTime(response.getSuccessTime())
                        .build()
        );
    }

    /**
     * 把第三方返回的支付状态落到库。
     *
     * @param expectStatus 期望的旧状态。更新时带上 `WHERE status = expectStatus`，
     *                     并发场景下（查单接口 + 补偿任务同时命中同一笔单）只有一个能更新成功，
     *                     调用方据此决定是否发「支付成功」通知，避免重复通知导致 trade 侧重复报名
     * @return true 表示本次真正完成了状态迁移，false 表示已被别人改过或更新异常
     */
    private boolean updatePayStatus2DB(PayStatusResponse response, Long id, Integer expectStatus) {
        try {
            boolean success = PayStatus.TRADE_SUCCESS.equalsValue(response.getPayStatus());
            return lambdaUpdate()
                    .set(PayOrder::getStatus, response.getPayStatus())
                    // 成功时必须落支付时间：回调路径（markPayOrderSuccess）是写的，查单兜底路径
                    // 原来不写，导致「回调丢了、靠补偿任务置成功」的订单 pay_success_time 永远为空，
                    // 前端/报表上支付时间缺失
                    .set(success, PayOrder::getPaySuccessTime, response.getSuccessTime())
                    .set(success, PayOrder::getNotifyStatus, NotifyStatus.CALLING.getValue())
                    .set(PayOrder::getResultCode, response.getCode() == null ? "" : response.getCode())
                    .set(PayOrder::getResultMsg, response.getMsg() == null ? "" : response.getMsg())
                    .eq(PayOrder::getId, id)
                    .eq(PayOrder::getStatus, expectStatus)
                    .update();
        } catch (Exception e) {
            log.error("更新支付单结果到数据时发生异常", e);
            return false;
        }
    }

    /**
     * 判断「查不到可支付状态」时是否允许关单。
     * <p>
     * 只认可两种情况：
     * ① 查单成功且明确是「待支付」—— 用户确实没付；
     * ② 渠道明确回复「交易不存在」—— 渠道侧压根没有这笔单，再等也等不到。
     * 其余（网络异常、签名错误、参数错误）一律不关，宁可多等一轮也不能把已付款的单误关。
     * </p>
     * <p>
     * ② 必须放行：补偿任务每轮只捞 size 条（默认 100），这类僵尸单如果永远关不掉，
     * 会一直排在扫描结果前面，把新产生的支付单挤出去，等于补偿链路整体失效。
     * </p>
     */
    private boolean canCloseOnTimeout(PayStatusResponse response) {
        if (response == null) {
            return false;
        }
        if (response.isSuccess()) {
            return PayStatus.WAIT_BUYER_PAY.equalsValue(response.getPayStatus());
        }
        // 支付宝：ACQ.TRADE_NOT_EXIST；微信：ORDER_NOT_EXIST
        return StringUtils.equalsAny(response.getCode(), "ACQ.TRADE_NOT_EXIST", "ORDER_NOT_EXIST");
    }

    /**
     * 关闭支付单：先关渠道，再关本地。
     * <p>
     * 顺序不能反。如果先把本地置为「已关闭」而渠道没关掉，用户手里的二维码仍然能付款，
     * 而补偿任务已经不会再扫到这笔单（只扫待支付）—— 结果是钱收了、单关了，
     * 而且日志上一点痕迹都没有。
     * </p>
     */
    private void closeOrder(Long id) {
        PayOrder payOrder = getById(id);
        if (payOrder == null || PayStatus.TRADE_CLOSED.equalsValue(payOrder.getStatus())) {
            return;
        }
        // 1.先关渠道侧订单。渠道不存在时只能关本地（对应 checkPayOrder 里渠道缺失的分支）
        IPayService payService = payServiceChannels.get(payOrder.getPayChannelCode());
        boolean channelClosed = payService == null
                || payService.closeOrder(payOrder.getPayOrderNo().toString());
        if (!channelClosed) {
            // 2.关单没成功，分三种情况处理
            PayStatusResponse response = queryFromChannel(payOrder);
            if (response == null) {
                // 2.1.查单本身异常（网络问题），无法判断用户是否已付，保持原状等下一轮
                log.warn("支付单{}关单失败且查单异常，保持待支付等下一轮重试", payOrder.getPayOrderNo());
                return;
            }
            if (response.isSuccess() && PayStatus.TRADE_SUCCESS.equalsValue(response.getPayStatus())) {
                // 2.2.查单之后用户刚好付了款：绝不能关，转为支付成功
                log.info("支付单{}在关单前已支付，转为支付成功处理", payOrder.getPayOrderNo());
                if (updatePayStatus2DB(response, payOrder.getId(), payOrder.getStatus())) {
                    sendPaySuccessMessage(response, payOrder);
                }
                return;
            }
            if (response.isSuccess()) {
                // 2.3.确认未支付但关单没成功，多半是网络抖动，保持原状等下一轮重试
                log.warn("支付单{}确认未支付但渠道关单未成功，等下一轮重试", payOrder.getPayOrderNo());
                return;
            }
            // 2.4.查单被渠道拒绝（交易不存在 / 参数错误）：渠道侧根本没有这笔可支付的单，
            //     继续等下去也永远关不掉，直接关本地
            log.warn("支付单{}在渠道侧不存在或不可查（code={}），直接关闭本地",
                    payOrder.getPayOrderNo(), response.getCode());
        }
        // 3.渠道确认已不可支付，本地条件更新
        boolean updated = lambdaUpdate()
                .set(PayOrder::getStatus, PayStatus.TRADE_CLOSED.getValue())
                .eq(PayOrder::getId, id)
                .eq(PayOrder::getStatus, payOrder.getStatus())
                .update();
        if (!updated) {
            log.debug("支付单{}状态已被其它路径更新，跳过本地关单", payOrder.getPayOrderNo());
        }
    }
}
