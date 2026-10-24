package com.zhiwei.pay.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.autoconfigure.redisson.annotations.Lock;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.pay.domain.po.PayOrder;
import com.zhiwei.pay.domain.po.RefundOrder;
import com.zhiwei.pay.mapper.RefundOrderMapper;
import com.zhiwei.pay.sdk.constants.PayConstants;
import com.zhiwei.pay.sdk.constants.PayErrorInfo;
import com.zhiwei.pay.sdk.dto.RefundApplyDTO;
import com.zhiwei.pay.sdk.dto.RefundResultDTO;
import com.zhiwei.pay.service.IPayOrderService;
import com.zhiwei.pay.service.IRefundOrderService;
import com.zhiwei.pay.third.IPayService;
import com.zhiwei.pay.third.model.RefundResponse;
import com.zhiwei.pay.third.model.RefundStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

import static com.zhiwei.pay.sdk.constants.PayErrorInfo.INVALID_PAY_CHANNEL;

/**
 * <p>
 * 退款订单 服务实现类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundOrderServiceImpl extends ServiceImpl<RefundOrderMapper, RefundOrder> implements IRefundOrderService {

    private final IPayOrderService payOrderService;

    private final RabbitMqHelper rabbitMqHelper;

    @Resource
    private Map<String, IPayService> payServiceChannels;

    /**
     * 申请退款。
     *
     * 注意：分布式锁必须加在这个 public 入口上。加在下面的 checkIdempotent 上是无效的，原因有两条：
     * ① LockAspect 用的是 Spring AOP 代理（@Around("@annotation")），private 方法不会被织入；
     * ② 本方法内部 this.checkIdempotent(...) 属于自调用，根本不经过代理。
     * 锁失效的后果不是「慢一点」而是「重复退款」：refund_order 的 biz_refund_order_no 没有唯一索引，
     * 两个并发请求都会查不到旧退款单、各插一条、各调一次第三方退款。
     */
    @Override
    @Lock(name = PayConstants.RedisKeyFormatter.REFUND_APPLY, waitTime = 5, autoUnlock = true)
    public RefundResultDTO applyRefund(RefundApplyDTO refundApplyDTO) {
        log.debug("准备申请退款，业务端退款订单号：{}", refundApplyDTO.getBizRefundOrderNo());
        // 1.幂等性校验
        RefundOrder refundOrder = checkIdempotent(refundApplyDTO);
        if (refundOrder == null) {
            // 为 null说明退款处理中，无需重新申请，返回false
            return RefundResultDTO.running().msg("退款执行中").build();
        }
        // 2.获取支付渠道
        IPayService payService = payServiceChannels.get(refundOrder.getPayChannelCode());
        if (payService == null) {
            log.error("退款异常，支付渠道不存在，业务单号：{}", refundApplyDTO.getBizOrderNo());
            throw new BadRequestException(INVALID_PAY_CHANNEL);
        }
        // 3.尝试退款
        RefundResponse refundResponse = payService.refundOrder(
                refundOrder.getPayOrderNo().toString(), refundOrder.getRefundOrderNo().toString(),
                refundOrder.getRefundAmount(), refundOrder.getTotalAmount());
        // 4.更新退款结果到数据库
        updateRefundStatus(refundResponse, refundOrder.getId());
        // 5.返回退款申请结果
        RefundResultDTO refundResultDTO = transferRefundResult(refundResponse);
        refundResultDTO.setBizRefundOrderId(refundOrder.getBizRefundOrderNo());
        refundResultDTO.setBizPayOrderId(refundOrder.getBizOrderNo());
        refundResultDTO.setRefundOrderNo(refundOrder.getRefundOrderNo());
        return refundResultDTO;
    }

    private void updateRefundStatus(RefundResponse refundResponse, Long id) {
        try {
            // 退款请求被支付宝拒绝时（如超额退款、交易已关闭），原来只写 result_code/result_msg、
            // status 停在 NOT_COMMIT(0)：对账看不到「失败」，同一单号重试还会被当成「未提交」反复打支付宝。
            // 这里在请求失败时把状态落成 FAILED，让失败可见、也不再被重试逻辑当成待提交。
            boolean failed = !refundResponse.getSuccess();
            lambdaUpdate()
                    .set(refundResponse.getSuccess(), RefundOrder::getStatus, refundResponse.getStatus())
                    .set(failed, RefundOrder::getStatus, RefundStatus.FAILED.getValue())
                    .set(refundResponse.getAmount() != null, RefundOrder::getRefundAmount, refundResponse.getAmount())
                    .set(refundResponse.getChannel() != null, RefundOrder::getRefundChannel, refundResponse.getChannel())
                    .set(RefundOrder::getResultCode, refundResponse.getCode() == null ? "" : refundResponse.getCode())
                    .set(RefundOrder::getResultMsg, refundResponse.getMsg() == null ? "" : refundResponse.getMsg())
                    .eq(RefundOrder::getId, id)
                    .update();
        } catch (Exception e) {
            log.error("更新退款单状态发生异常", e);
        }
    }

    private RefundOrder checkIdempotent(RefundApplyDTO refundApplyDTO) {
        // 1.查询出退款单对应的支付单
        PayOrder payOrder = payOrderService.queryByBizOrderNo(refundApplyDTO.getBizOrderNo());
        if (payOrder == null) {
            // 支付单为空，无法退款
            throw new BizIllegalException(PayErrorInfo.PAY_ORDER_NOT_FOUND);
        }
        // 2.判断是否已经支付成功
        if (!payOrder.success()) {
            // 订单尚未支付，无法退款
            throw new BizIllegalException(PayErrorInfo.PAY_ORDER_NOT_SUCCESS);
        }
        // 2.1.退款金额不能超过实付金额。
        // 原来完全没有这道校验，超额退款会一路打到支付宝，返回的还是「交易已经关闭」这种误导性文案
        Integer refundAmount = refundApplyDTO.getRefundAmount();
        if (refundAmount == null || refundAmount <= 0) {
            throw new BadRequestException("退款金额必须为正数");
        }
        if (refundAmount > payOrder.getAmount()) {
            log.error("退款金额超过支付金额，业务单号：{}，支付金额：{}，退款金额：{}",
                    refundApplyDTO.getBizOrderNo(), payOrder.getAmount(), refundAmount);
            throw new BadRequestException(PayErrorInfo.REFUND_AMOUNT_EXCEED);
        }

        // 3.查询当前订单是否已经有退款单
        RefundOrder oldRefundOrder = queryByBizRefundOrder(refundApplyDTO.getBizRefundOrderNo());
        // 3.1.判断是否为空
        if (oldRefundOrder == null) {
            // 本订单第一次退款，需要生成新退款单
            RefundOrder refundOrder = BeanUtils.toBean(refundApplyDTO, RefundOrder.class);
            refundOrder.setRefundOrderNo(IdWorker.getId());
            refundOrder.setIsSplit(payOrder.getAmount().equals(refundApplyDTO.getRefundAmount()));
            refundOrder.setPayOrderNo(payOrder.getPayOrderNo());
            refundOrder.setTotalAmount(payOrder.getAmount());
            refundOrder.setPayChannelCode(payOrder.getPayChannelCode());
            save(refundOrder);
            return refundOrder;
        }

        // 3.2.判断退款是否已经成功，如果成功不能退款
        if (oldRefundOrder.success()) {
            throw new BizIllegalException(PayErrorInfo.REPEAT_REFUND_ORDER);
        }

        // 3.3.判断退款是否已经失败，如果失败直接结束
        if (oldRefundOrder.failed()) {
            throw new BizIllegalException(PayErrorInfo.REFUND_FAILED);
        }

        // 3.4.退款请求未提交，重新提交
        if (oldRefundOrder.notCommit()) {
            // 需要先更新退款数据
            oldRefundOrder.setRefundAmount(refundApplyDTO.getRefundAmount());
            updateById(oldRefundOrder);
            return oldRefundOrder;
        }

        // 3.5.退款正在进行中，什么都不做
        return null;
    }

    private RefundOrder queryByBizRefundOrder(Long bizRefundOrderId) {
        return lambdaQuery()
                .eq(RefundOrder::getBizRefundOrderNo, bizRefundOrderId)
                .one();
    }

    @Override
    public RefundResultDTO queryRefundResult(Long bizRefundOrderId) {
        // 1.查询退款单
        RefundOrder refundOrder = queryByBizRefundOrder(bizRefundOrderId);
        // 2.判断是否为空
        if (refundOrder == null) {
            return null;
        }
        // 3.判断退款单是否退款成功
        if (refundOrder.success()) {
            return RefundResultDTO.success()
                    .refundOrderNo(refundOrder.getRefundOrderNo())
                    .bizPayOrderId(refundOrder.getBizOrderNo())
                    .bizRefundOrderId(refundOrder.getBizRefundOrderNo())
                    .refundChannel(refundOrder.getRefundChannel())
                    .build();
        }
        // 4.判断退款单是否退款失败
        if (refundOrder.failed()) {
            return RefundResultDTO.failed()
                    .msg(refundOrder.getResultMsg())
                    .refundOrderNo(refundOrder.getRefundOrderNo())
                    .build();
        }
        // 5.退款状态未知，需求去第三方查询，选择查询渠道
        IPayService payService = payServiceChannels.get(refundOrder.getPayChannelCode());
        if (payService == null) {
            log.error("退款异常，支付渠道不存在，业务单号：{}", bizRefundOrderId);
            throw new BadRequestException(INVALID_PAY_CHANNEL);
        }
        // 6.去第三方查询一下
        RefundResponse refundResponse = payService.queryRefundStatus(
                refundOrder.getPayOrderNo().toString(), refundOrder.getRefundOrderNo().toString());
        // 6.1.更新数据库退款单状态
        updateRefundStatus(refundResponse, refundOrder.getId());

        // 6.2.返回结果
        RefundResultDTO refundResultDTO = transferRefundResult(refundResponse);
        refundResultDTO.setRefundOrderNo(refundOrder.getRefundOrderNo());
        refundResultDTO.setBizRefundOrderId(bizRefundOrderId);
        return refundResultDTO;
    }

    @Override
    public RefundOrder queryByRefundOrderNo(Long refundOrderNo) {
        return lambdaQuery()
                .eq(RefundOrder::getRefundOrderNo, refundOrderNo)
                .one();
    }

    @Override
    public PageDTO<RefundOrder> queryRefundingOrderByPage(int shardIndex, int shardTotal, int size) {
        // 1.分页和排序条件（shardIndex 从 0 开始）
        Page<RefundOrder> page = new Page<>(1, size);
        page.addOrder(OrderItem.asc("id"));
        // 2.查询
        Page<RefundOrder> result = lambdaQuery()
                .eq(RefundOrder::getStatus, RefundStatus.UN_KNOWN.getValue())
                // 真正的分片：按 id 对分片总数取模，避免拿分片号当页码导致数据漂移时重复或漏单
                .apply(shardTotal > 1, "MOD(id, {0}) = {1}", shardTotal, shardIndex)
                .page(page);
        return PageDTO.of(result);
    }

    @Override
    public void checkRefundOrder(RefundOrder refundOrder) {
        // 1.获取退款渠道
        String payChannelCode = refundOrder.getPayChannelCode();
        IPayService payService = payServiceChannels.get(payChannelCode);
        if (payService == null) {
            log.error("支付渠道不存在，退款单号：{}", refundOrder.getId());
            // 异常订单，需要关闭支付单
            closeOrder(refundOrder.getId());
            return;
        }
        // 2.查询退款状态
        RefundResponse refundResponse = payService.queryRefundStatus(
                refundOrder.getPayOrderNo().toString(), refundOrder.getRefundOrderNo().toString());

        if (refundResponse.getStatus().equals(refundOrder.getStatus())) {
            // 退款状态没有变化，不做处理
            return;
        }

        // 3.更新数据库退款单状态
        updateRefundStatus(refundResponse, refundOrder.getId());

        // 4.发送MQ通知业务端
        rabbitMqHelper.send(
                MqConstants.Exchange.PAY_EXCHANGE,
                MqConstants.Key.REFUND_CHANGE,
                RefundResultDTO.success()
                        .refundOrderNo(refundOrder.getRefundOrderNo())
                        .bizPayOrderId(refundOrder.getBizOrderNo())
                        .bizRefundOrderId(refundOrder.getBizRefundOrderNo())
                        .refundChannel(refundOrder.getRefundChannel())
                        .build()
        );
    }

    @Override
    public RefundResultDTO queryRefundDetail(Long bizRefundOrderId) {
        // 1.查询退款单
        RefundOrder refundOrder = queryByBizRefundOrder(bizRefundOrderId);
        // 2.判断是否为空
        if (refundOrder == null) {
            throw new BadRequestException(PayErrorInfo.REFUND_ORDER_NOT_FOUND);
        }
        // 3.返回结果
        return RefundResultDTO.builder()
                .status(refundOrder.getStatus())
                .msg(refundOrder.getResultMsg())
                .bizRefundOrderId(bizRefundOrderId)
                .bizPayOrderId(refundOrder.getBizOrderNo())
                .payOrderNo(refundOrder.getPayOrderNo())
                .refundOrderNo(refundOrder.getRefundOrderNo())
                .payChannel(refundOrder.getRefundChannel())
                .build();
    }

    private void closeOrder(Long id) {
        RefundOrder refundOrder = new RefundOrder();
        refundOrder.setId(id);
        refundOrder.setStatus(RefundStatus.FAILED.getValue());
        updateById(refundOrder);
    }

    private RefundResultDTO transferRefundResult(RefundResponse refundResponse) {
        if (!refundResponse.getSuccess()) {
            return RefundResultDTO.failed()
                    .msg(refundResponse.getMsg()).refundChannel(refundResponse.getChannel()).build();
        }
        if (refundResponse.refundSuccess()) {
            return RefundResultDTO.success().refundChannel(refundResponse.getChannel()).build();
        }
        return RefundResultDTO.running()
                .msg(refundResponse.getMsg()).refundChannel(refundResponse.getChannel()).build();
    }
}
