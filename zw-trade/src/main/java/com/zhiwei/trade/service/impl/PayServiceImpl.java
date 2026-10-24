package com.zhiwei.trade.service.impl;

import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.utils.AssertUtils;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.pay.sdk.client.PayClient;
import com.zhiwei.pay.sdk.constants.PayType;
import com.zhiwei.pay.sdk.dto.PayApplyDTO;
import com.zhiwei.pay.sdk.dto.PayChannelDTO;
import com.zhiwei.pay.sdk.dto.PayResultDTO;
import com.zhiwei.trade.config.TradeProperties;
import com.zhiwei.trade.constants.OrderStatus;
import com.zhiwei.trade.constants.TradeErrorInfo;
import com.zhiwei.trade.domain.dto.OrderDelayQueryDTO;
import com.zhiwei.trade.domain.dto.PayApplyFormDTO;
import com.zhiwei.trade.domain.po.Order;
import com.zhiwei.trade.domain.po.OrderDetail;
import com.zhiwei.trade.domain.vo.PayChannelVO;
import com.zhiwei.trade.service.IOrderDetailService;
import com.zhiwei.trade.service.IOrderService;
import com.zhiwei.trade.service.IPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static com.zhiwei.common.constants.MqConstants.Exchange.TRADE_DELAY_EXCHANGE;
import static com.zhiwei.common.constants.MqConstants.Key.ORDER_DELAY_KEY;
import static com.zhiwei.trade.constants.TradeErrorInfo.ORDER_NOT_EXISTS;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayServiceImpl implements IPayService {

    private final PayClient payClient;
    private final IOrderService orderService;
    private final IOrderDetailService detailService;
    private final TradeProperties tradeProperties;
    private final RabbitMqHelper mqHelper;

    @Override
    public List<PayChannelVO> queryPayChannels() {
        List<PayChannelDTO> list = payClient.listAllPayChannels();
        if (list == null) {
            return CollUtils.emptyList();
        }
        return list.stream()
                .filter(p -> p.getStatus() == 1)
                .map(p -> BeanUtils.copyBean(p, PayChannelVO.class))
                .collect(Collectors.toList());
    }

    @Override
    public String applyPayOrder(PayApplyFormDTO payApply) {
        Long orderId = payApply.getOrderId();
        // 1.查询订单信息
        Order order = orderService.getById(orderId);
        if (order == null) {
            throw new BadRequestException(ORDER_NOT_EXISTS);
        }
        // 2.判断订单状态
        if (!OrderStatus.NO_PAY.equalsValue(order.getStatus())) {
            // 订单已经支付或关闭。
            // 用 BadRequestException（400）而不是 BizIllegalException（500）：
            // 这是「客户端拿着一个不该支付的订单来支付」，属于请求本身不合法，
            // 不是服务端故障。500 会让前端/监控把它当服务异常，日志里也混在真错误里。
            throw new BadRequestException(TradeErrorInfo.ORDER_ALREADY_FINISH);
        }
        // 3.判断订单是否已经超时
        if (order.getCreateTime().plusMinutes(tradeProperties.getPayOrderTTLMinutes()).isBefore(LocalDateTime.now())) {
            // 订单已经超时，无法支付，同上按 400 返回。
            // ★ 这里顺手把订单关掉，而不是只抛异常。
            //   定时任务最多每分钟跑一次，超时之后到任务真正关单之间有个窗口：
            //   用户看到的还是「待支付」，点支付却被告知超时，退回列表还是「待支付」，
            //   要等任务跑完才翻成「已关闭」—— 状态自相矛盾。
            //   用户点支付这一刻，正是发现「这单已经超时」最准的时机，顺手关掉，
            //   退回列表看到的就是「已关闭」，不用等下一轮。
            //   关单失败不影响本次响应：订单仍是待支付，下一轮任务还会继续处理。
            boolean closed = false;
            try {
                closed = orderService.closeOrderInternal(order, "订单超时未支付，系统自动关闭");
            } catch (Exception e) {
                log.warn("订单{}超时关单失败，交给定时任务处理：{}", orderId, e.getMessage());
            }
            if (!closed) {
                // 没关成功，未必是「关不掉」——也可能是用户刚好在这一刻付了款：
                // closeOrderInternal 在 pay 回复已支付时会把订单转成支付成功并返回 false。
                // 这种情况再抛「订单已超时」，用户会以为钱白付了，必须按「已支付」回复。
                Order latest = orderService.getById(orderId);
                if (latest != null && !OrderStatus.NO_PAY.equalsValue(latest.getStatus())) {
                    throw new BadRequestException(TradeErrorInfo.ORDER_ALREADY_FINISH);
                }
            }
            throw new BadRequestException(TradeErrorInfo.ORDER_OVER_TIME);
        }
        // 4.查询订单详情
        List<OrderDetail> details = detailService.queryByOrderId(orderId);
        AssertUtils.isNotEmpty(details, ORDER_NOT_EXISTS);

        // 5.封装下单参数
        PayApplyDTO payApplyDTO = PayApplyDTO.builder()
                .bizOrderNo(orderId)
                .amount(order.getRealAmount())
                .orderInfo(details.get(0).getName())
                .bizUserId(order.getUserId())
                .payType(PayType.NATIVE.getValue())
                .payChannelCode(payApply.getPayChannelCode())
                .build();
        String url = payClient.applyPayOrder(payApplyDTO);
        // 6.通过延迟队列，异步查询支付结果
        sendDelayQueryMessage(OrderDelayQueryDTO.init(orderId));
        return url;
    }

    private void sendDelayQueryMessage(OrderDelayQueryDTO message) {
        mqHelper.sendDelayMessage(
                TRADE_DELAY_EXCHANGE,
                ORDER_DELAY_KEY,
                message, Duration.ofMillis(message.removeFirst()));
    }

    @Override
    public void queryPayResult(OrderDelayQueryDTO message) {
        // 1.获取订单信息
        Long orderId = message.getOrderId();
        if (orderId == null) {
            // 队列里可能混进结构不对的消息（手工投递、上游字段改名、别的服务误发）。
            // 不判空的话会白打一条 `WHERE id=null` 的 SQL 再抛 ERROR，看起来像业务故障。
            log.error("延迟查询消息缺少订单id，已丢弃：{}", message);
            return;
        }
        Order order = orderService.getById(orderId);
        if (order == null) {
            log.error("要查询状态的订单：{}不存在", orderId);
            return;
        }
        // 2.判断订单状态
        if (!OrderStatus.NO_PAY.equalsValue(order.getStatus())) {
            // 订单已经支付或关闭，任务结束
            return;
        }
        // 3.查询支付状态
        PayResultDTO payResult = payClient.queryPayResult(orderId);
        int status = payResult.getStatus();
        if(PayResultDTO.SUCCESS != status){
            // 3.1.支付中或支付失败，需要重试查询
            if(message.getDelayMillis().size() == 0){
                // 重试次数用尽，结束
                log.warn("订单{}支付结果查询重试次数已用尽，最后状态：{}", orderId, status);
                return;
            }
            // 发送延迟查询消息，再次查询支付状态
            sendDelayQueryMessage(message);
            // 必须 return：未支付时绝不能落到下面的 handlePaySuccess。
            // 原版这里漏了 return，只是因为 pay 侧 queryPayResult 没回传 bizOrderId，
            // handlePaySuccess 里 getById(null) 查不到订单而静默退出，才一直没有暴露。
            // 补上 bizOrderId 之后若不同时补这个 return，会把「待支付」订单直接改成「已支付」，
            // 而且 pay_time 还是 null —— 等于凭空造出一笔已支付订单。
            return;
        }
        // 3.2.支付成功
        orderService.handlePaySuccess(payResult);
    }
}
