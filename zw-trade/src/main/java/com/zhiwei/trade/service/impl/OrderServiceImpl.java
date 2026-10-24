package com.zhiwei.trade.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.api.client.course.CourseClient;
import com.zhiwei.api.client.promotion.PromotionClient;
import com.zhiwei.api.constants.CourseStatus;
import com.zhiwei.api.dto.course.CourseSimpleInfoDTO;
import com.zhiwei.api.dto.promotion.CouponDiscountDTO;
import com.zhiwei.api.dto.promotion.OrderCouponDTO;
import com.zhiwei.api.dto.promotion.OrderCourseDTO;
import com.zhiwei.api.dto.trade.OrderBasicDTO;
import com.zhiwei.common.autoconfigure.idempotent.annotations.Idempotent;
import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.exceptions.DbException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.pay.sdk.client.PayClient;
import com.zhiwei.pay.sdk.dto.PayResultDTO;
import com.zhiwei.trade.config.ThreadPoolConfig;
import java.util.Collections;
import com.zhiwei.trade.config.TradeProperties;
import com.zhiwei.trade.constants.OrderStatus;
import com.zhiwei.trade.constants.RefundStatus;
import com.zhiwei.trade.constants.TradeErrorInfo;
import com.zhiwei.trade.domain.dto.PlaceOrderDTO;
import com.zhiwei.trade.domain.po.Order;
import com.zhiwei.trade.domain.po.OrderDetail;
import com.zhiwei.trade.domain.query.OrderPageQuery;
import com.zhiwei.trade.domain.vo.*;
import com.zhiwei.trade.mapper.OrderMapper;
import com.zhiwei.trade.service.ICartService;
import com.zhiwei.trade.service.IOrderDetailService;
import com.zhiwei.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.seata.spring.annotation.GlobalTransactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.zhiwei.common.constants.ErrorInfo.Msg.OPERATE_FAILED;
import static com.zhiwei.trade.constants.TradeErrorInfo.ORDER_ALREADY_FINISH;
import static com.zhiwei.trade.constants.TradeErrorInfo.ORDER_NOT_EXISTS;

/**
 * <p>
 * 订单 服务实现类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final CourseClient courseClient;
    private final IOrderDetailService detailService;
    private final ICartService cartService;
    private final TradeProperties tradeProperties;
    private final RabbitMqHelper rabbitMqHelper;
    private final PromotionClient promotionClient;
    private final ThreadPoolConfig threadPoolConfig;
    private final PayClient payClient;
    /**
     * 自身的代理对象。queryOrderStatus 里要调用带 @Transactional 的 handlePaySuccess，
     * 用 this.xxx() 属于自调用、会绕过 AOP 代理导致事务失效，所以通过 ObjectProvider 拿代理。
     * 用 ObjectProvider 而不是直接注入 IOrderService，是为了避免与自身形成循环依赖。
     */
    private final ObjectProvider<IOrderService> selfProvider;

    @Override
    @GlobalTransactional
    @Transactional
    // 幂等键取客户端预生成的订单号：同一笔订单号在窗口期内只会真正创建一次。
    // keepResult=true 让重复提交直接拿到首次的返回结果，而不是抛"主键冲突"给用户看
    @Idempotent(key = "order:place:#{placeOrderDTO.orderId}",
            keepResult = true, timeout = 10, resultTimeout = 300,
            message = "订单已提交，请勿重复下单")
    public PlaceOrderResultVO placeOrder(PlaceOrderDTO placeOrderDTO) {
        Long userId = UserContext.getUserId();
        // 1.查询课程费用信息，如果不可购买，这里直接报错
        List<Long> courseIds = placeOrderDTO.getCourseIds();
        List<CourseSimpleInfoDTO> courseInfos = getOnShelfCourse(courseIds);
        // 2.封装订单信息
        Order order = new Order();
        // 2.1.计算订单金额
        Integer totalAmount = courseInfos.stream()
                .map(CourseSimpleInfoDTO::getPrice).reduce(Integer::sum).orElse(0);
        //  2.2.计算优惠金额
        order.setDiscountAmount(0);
        List<Long> couponIds = placeOrderDTO.getCouponIds();
        CouponDiscountDTO discount = null;
        if (CollUtils.isNotEmpty(couponIds)) {
            List<OrderCourseDTO> orderCourses = courseInfos
                    .stream()
                    .map(ci -> new OrderCourseDTO().setId(ci.getId()).setCateId(ci.getThirdCateId()).setPrice(ci.getPrice()))
                    .collect(Collectors.toList());
            discount = promotionClient.queryDiscountDetailByOrder(new OrderCouponDTO(couponIds, orderCourses));
            if (discount != null && discount.getDiscountAmount() != null) {
                order.setDiscountAmount(discount.getDiscountAmount());
                order.setCouponIds(discount.getIds());
            }
        }
        Integer realAmount = totalAmount - order.getDiscountAmount();
        // 2.3.封装其它信息
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setRealAmount(realAmount);
        order.setStatus(OrderStatus.NO_PAY.getValue());
        order.setMessage(OrderStatus.NO_PAY.getProgressName());
        // 2.4.订单id
        Long orderId = placeOrderDTO.getOrderId();
        order.setId(orderId);

        // 3.封装订单详情
        List<OrderDetail> orderDetails = new ArrayList<>(courseInfos.size());
        for (CourseSimpleInfoDTO courseInfo : courseInfos) {
            Integer discountValue = discount == null ? 0 : discount.getDiscountDetail().getOrDefault(courseInfo.getId(), 0);
            orderDetails.add(packageOrderDetail(courseInfo, order, discountValue));
        }

        // 4.写入数据库
        saveOrderAndDetails(order, orderDetails);

        // 5.删除购物车数据
        cartService.deleteCartByUserAndCourseIds(userId, courseIds);
        //6.核销优惠卷
        if (discount != null && CollUtils.isNotEmpty(discount.getIds())) {
            promotionClient.writeOffCoupon(discount.getIds());
        }

        //7. 构建下单结果
        return PlaceOrderResultVO.builder()
                .orderId(orderId)
                .payAmount(realAmount)
                .status(order.getStatus())
                .payOutTime(LocalDateTime.now().plusMinutes(tradeProperties.getPayOrderTTLMinutes()))
                .build();
    }

    private List<CourseSimpleInfoDTO> getOnShelfCourse(List<Long> courseIds) {
        // 1.查询课程
        List<CourseSimpleInfoDTO> courseInfos = courseClient.getSimpleInfoList(courseIds);
        LocalDateTime now = LocalDateTime.now();
        // 2.判断状态
        for (CourseSimpleInfoDTO courseInfo : courseInfos) {
            // 2.1.检查课程是否上架
            if (!CourseStatus.SHELF.equalsValue(courseInfo.getStatus())) {
                throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_FOR_SALE);
            }
            // 2.2.检查课程是否过期
            if (courseInfo.getPurchaseEndTime().isBefore(now)) {
                throw new BizIllegalException(TradeErrorInfo.COURSE_EXPIRED);
            }
        }
        return courseInfos;
    }


    @Override
    @Transactional
    // 免费课报名的订单号由服务端 IdWorker 现场生成，每次调用都不同，
    // 因此用户连点两次真的会产生两笔订单 + 两条报名 MQ，这里必须按「用户 + 课程」维度做幂等
    @Idempotent(key = "order:enroll:#{userId}:#{courseId}",
            keepResult = true, timeout = 10, resultTimeout = 300,
            message = "报名请求正在处理中，请勿重复提交")
    public PlaceOrderResultVO enrolledFreeCourse(Long courseId) {
        Long userId = UserContext.getUserId();
        // 1.查询课程信息
        List<Long> cIds = CollUtils.singletonList(courseId);
        List<CourseSimpleInfoDTO> courseInfos = getOnShelfCourse(cIds);
        if (CollUtils.isEmpty(courseInfos)) {
            // 课程不存在
            throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_EXISTS);
        }
        CourseSimpleInfoDTO courseInfo = courseInfos.get(0);
        if (!courseInfo.getFree()) {
            // 非免费课程，直接报错
            throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_FREE);
        }
        // 2.创建订单
        Order order = new Order();
        // 2.1.基本信息
        order.setUserId(userId);
        order.setTotalAmount(0);
        order.setDiscountAmount(0);
        order.setRealAmount(0);
        order.setStatus(OrderStatus.ENROLLED.getValue());
        order.setFinishTime(LocalDateTime.now());
        order.setMessage(OrderStatus.ENROLLED.getProgressName());
        // 2.2.订单id
        Long orderId = IdWorker.getId(order);
        order.setId(orderId);

        // 3.订单详情
        OrderDetail detail = packageOrderDetail(courseInfo, order, 0);

        // 4.写入数据库
        saveOrderAndDetails(order, CollUtils.singletonList(detail));

        // 5.发送MQ消息，通知报名成功
        rabbitMqHelper.send(
                MqConstants.Exchange.ORDER_EXCHANGE,
                MqConstants.Key.ORDER_PAY_KEY,
                OrderBasicDTO.builder()
                        .orderId(orderId)
                        .userId(userId)
                        .courseIds(cIds)
                        .finishTime(order.getFinishTime())
                        .build()
        );
        // 6.返回vo
        return PlaceOrderResultVO.builder()
                .orderId(orderId)
                .payAmount(0)
                .status(order.getStatus())
                .build();
    }

    @Override
    public OrderConfirmVO prePlaceOrder(List<Long> courseIds) {
        // 1.查询课程信息
        List<CourseSimpleInfoDTO> courseInfos = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(courseInfos)) {
            throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_EXISTS);
        }
        List<OrderCourseVO> courses = BeanUtils.copyList(courseInfos, OrderCourseVO.class);
        // 2.计算总价
        int total = courseInfos.stream().mapToInt(CourseSimpleInfoDTO::getPrice).sum();
        //  3.计算折扣
        List<OrderCourseDTO> orderCourses = courseInfos
                .stream()
                .map(ci -> new OrderCourseDTO().setId(ci.getId()).setPrice(ci.getPrice()).setCateId(ci.getThirdCateId()))
                .collect(Collectors.toList());
        List<CouponDiscountDTO> discountSolution = promotionClient.findDiscountSolution(orderCourses);

        // 4.生成订单id
        long orderId = IdWorker.getId();
        // 5.组织返回
        OrderConfirmVO vo = new OrderConfirmVO();
        vo.setOrderId(orderId);
        vo.setTotalAmount(total);
        vo.setDiscounts(discountSolution);
        vo.setCourses(courses);
        return vo;
    }

    private OrderDetail packageOrderDetail(CourseSimpleInfoDTO courseInfo, Order order, Integer discountValue) {
        OrderDetail detail = new OrderDetail();
        detail.setUserId(order.getUserId());
        detail.setOrderId(order.getId());
        detail.setStatus(order.getStatus());
        detail.setCourseId(courseInfo.getId());
        detail.setPrice(courseInfo.getPrice());
        detail.setCoverUrl(courseInfo.getCoverUrl());
        detail.setName(courseInfo.getName());
        detail.setValidDuration(courseInfo.getValidDuration());
        detail.setDiscountAmount(discountValue);
        detail.setRealPayAmount(courseInfo.getPrice() - detail.getDiscountAmount());
        return detail;
    }

    @Override
    @Transactional
    public void saveOrderAndDetails(Order order, List<OrderDetail> orderDetails) {
        // 4.1.写订单
        boolean success = save(order);
        if (!success) {
            throw new DbException(TradeErrorInfo.PLACE_ORDER_FAILED);
        }
        // 4.2.写订单详情
        if (orderDetails.size() == 1) {
            success = detailService.save(orderDetails.get(0));
        } else {
            success = detailService.saveBatch(orderDetails);
        }
        if (!success) {
            throw new DbException(TradeErrorInfo.PLACE_ORDER_FAILED);
        }
    }

    @Override
    @GlobalTransactional
    @Transactional
    public void cancelOrder(Long orderId) {
        Long userId = UserContext.getUserId();
        // 1.查询订单
        Order order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BadRequestException(ORDER_NOT_EXISTS);
        }
        // 2.判断订单状态是否已经取消，幂等判断
        if (OrderStatus.CLOSED.equalsValue(order.getStatus())) {
            // 订单已经取消，无需重复操作
            return;
        }
        // 3.判断订单是否未支付，只有未支付订单才可以取消
        if (!OrderStatus.NO_PAY.equalsValue(order.getStatus())) {
            // 用 400 而不是 500：订单已支付/已关闭还来取消，是请求本身不合法。
            // 实测用户付款成功后点「取消订单」拿到 500，前端和监控都会当服务端故障。
            throw new BadRequestException(ORDER_ALREADY_FINISH);
        }
        // 4.关单前必须先查单。
        // 用户完全可能已经扫码付款、只是前端还没刷新（状态同步有 5~8 秒延迟），
        // 此时点「取消订单」直接关单会造成「钱已收、订单已关闭」：
        // 之后 handlePaySuccess 的条件更新 eq(status, NO_PAY) 会失败并静默跳过，
        // 用户既没课上、钱也扣了，而且从日志上几乎看不出来。
        // 微信官方《支付回调和查单实现指引》5.4 的要求就是：先查单，NOTPAY 才关单，SUCCESS 不关单。
        order = syncFromPayCenter(order);
        if (!OrderStatus.NO_PAY.equalsValue(order.getStatus())) {
            // 查单确认已支付，取消请求作废。
            // 这里刻意不抛异常：syncFromPayCenter 内部可能已经完成了「更新订单 + 发报名消息」，
            // 抛异常会让当前事务回滚，而 MQ 已经发出去了，反而制造出「订单回滚但报名成功」的不一致。
            // 直接返回即可，前端刷新后会看到订单已支付。
            log.info("订单{}查单确认已支付，取消请求已忽略", orderId);
            return;
        }
        // 5.关支付中心 + 关本地订单 + 退优惠券。
        //    与定时任务超时关单共用同一套逻辑，避免两处关单顺序/补偿走偏
        boolean closed = closeOrderInternal(order, "用户取消订单");
        if (!closed) {
            // 没关成功，可能是「关单瞬间用户刚好付款了」，也可能是 pay 侧暂时关不掉渠道单。
            // 前者已被 closeOrderInternal 内部转成支付成功，不该报错；只有订单仍是待支付时才提示用户。
            // 不做这个区分的话，用户点「取消订单」会看到「什么都没发生」，比明确报错更糟。
            Order latest = getById(orderId);
            if (latest != null && OrderStatus.NO_PAY.equalsValue(latest.getStatus())) {
                throw new BizIllegalException("订单支付状态确认中，请稍后再试");
            }
        }
    }

    @Override
    @Transactional
    public boolean closeOrderInternal(Order order, String reason) {
        Long orderId = order.getId();
        // 1.幂等判断：非待支付直接结束。
        //    并发场景很常见 —— 定时任务扫到这条单的同时用户点了取消，或者查单刚好把状态刷成了已支付
        if (!OrderStatus.NO_PAY.equalsValue(order.getStatus())) {
            return false;
        }
        // 2.先让 pay 侧关掉第三方渠道的订单。
        //    这一步是整个关单流程里最关键的：用户手里的二维码是支付宝/微信生成的，
        //    只把本地 order / pay_order 置成已关闭没有任何用，二维码在渠道侧依然有效、依然能付款。
        //    pay 侧 closeOrder 内部已经实现了「先关渠道 → 关不掉就查单 → 已支付则转成功 → 确认未支付才关本地」，
        //    这里直接复用，不重复实现。
        PayResultDTO payResult;
        try {
            // 密钥由 PayInnerTokenFeignConfig 统一注入（读 zw.pay.inner-token），业务代码不再传参
            payResult = payClient.closePayOrder(orderId);
        } catch (Exception e) {
            // pay 不可用 / 网络抖动：无法判断用户是否已经付款，本轮必须放弃。
            // 绝不能「关不掉就当没这回事」直接关本地 —— 那正是资损路径。
            // 定时任务下一轮会重新扫到这条单
            log.warn("订单{}关闭支付单失败，本轮跳过关单：{}", orderId, e.getMessage());
            return false;
        }
        // 3.关单瞬间用户刚好付了款：绝不能关，转成支付成功。
        //    与 cancelOrder 里「查单确认已支付则不取消」的处理保持一致
        if (payResult != null && PayResultDTO.SUCCESS == payResult.getStatus()) {
            log.info("订单{}在关单前已支付，转为支付成功处理", orderId);
            // 走代理调用，保证 handlePaySuccess 上的 @Transactional 生效
            selfProvider.getObject().handlePaySuccess(payResult);
            return false;
        }
        // 4.只有 pay 明确回复「已关闭 / 无支付单」才允许关本地。
        //    PAYING 表示渠道关单失败且无法确认支付状态，此时二维码可能还能付款，必须等下一轮
        if (payResult == null || PayResultDTO.FAILED != payResult.getStatus()) {
            log.warn("订单{}的支付单暂时无法关闭（{}），本轮跳过",
                    orderId, payResult == null ? "返回为空" : payResult.getMsg());
            return false;
        }
        // 5.渠道已确认不可支付，关本地订单。条件更新保证并发安全
        boolean success = lambdaUpdate()
                .set(Order::getStatus, OrderStatus.CLOSED.getValue())
                .set(Order::getMessage, reason)
                .set(Order::getCloseTime, LocalDateTime.now())
                .eq(Order::getStatus, OrderStatus.NO_PAY.getValue())
                .eq(Order::getId, orderId)
                .update();
        if (!success) {
            // 状态已被别的路径改掉（用户支付成功 / 另一次关单先完成），不是错误
            return false;
        }
        // 6.更新订单条目的状态。
        //    ⚠️ 这一步失败绝不能回滚整笔事务。走到这里时，第三方渠道的支付单已经关闭、
        //    order 的状态也已经改成已关闭，这两件事都不可回退。
        //    若因为明细缺失（人为删过 / 下单时写失败）就抛异常回滚，订单会退回「待支付」，
        //    结果是「渠道已关 + 本地待支付」；下一轮任务重新扫到它、再失败一次，
        //    连续失败触发 MAX_CONSECUTIVE_FAIL 熔断后，排在它后面的所有超时订单全部堵死。
        //    所以这里只记日志。用 warn 而不是 error：明细缺失是数据问题，不是关单失败。
        if (!detailService.updateStatusByOrderId(orderId, OrderStatus.CLOSED.getValue())) {
            log.warn("订单{}关闭成功，但订单明细状态更新影响0行（明细缺失），已跳过不影响关单", orderId);
        }

        // 7.退款优惠券。
        //    ⚠️ promotion 的 refundCoupon 第一行就是 UserContext.getUserId()，按用户隔离查 user_coupon。
        //    定时任务线程没有用户上下文，直接调会一条券都查不到、静默不退 —— 用户白丢一张券，且日志无异常。
        //    这里手动把订单所属用户放进上下文，FeignRelayUserInterceptor 会把它转成 user-info 请求头传给 promotion。
        //    用「保存-恢复」而不是直接 remove：用户主动取消这条路径进来时，上下文本来就该保留。
        //    ⚠️ 退券失败同样不能让整笔回滚（与第 6 步同理，渠道单和订单都已关闭且不可回退）。
        //    但券是用户资产，不能像明细那样只记 warn 了事：静默吞掉等于让用户白丢一张券、且没人知道。
        //    所以打 error 并带固定前缀，方便按「需人工补偿」grep 出来手工退还。
        //    注意这个日志可能误报：promotion 侧其实已经退成功、只是响应超时，也会走到这里。
        //    人工补偿前先查一下 user_coupon 的实际状态，别重复退。
        if (CollUtils.isNotEmpty(order.getCouponIds())) {
            Long originUserId = UserContext.getUserId();
            try {
                UserContext.setUserId(order.getUserId());
                promotionClient.refundCoupon(order.getCouponIds());
            } catch (Exception e) {
                log.error("【需人工补偿】订单{}关闭成功但退券失败，couponIds={}，请核对后手动退还",
                        orderId, order.getCouponIds(), e);
            } finally {
                if (originUserId == null) {
                    UserContext.removeUserId();
                } else {
                    UserContext.setUserId(originUserId);
                }
            }
        }
        return true;
    }

    @Override
    public List<Order> queryTimeoutNoPayOrders(LocalDateTime deadline, Order after,
                                               int shardIndex, int shardTotal, int size) {
        // 分页而不是手拼 LIMIT：size 来自任务参数，拼字符串将来容易出注入问题。
        // ★ 第三个参数 searchCount = false 必须给：MyBatis-Plus 的分页插件默认会先跑一次
        //   `SELECT COUNT(*)` 算总数，而这里只要前 N 条、根本不用总数。
        //   实测日志里每轮都有一条 selectList_mpCount，待支付订单堆积越多这条越贵
        //   （要数完整个索引区间），纯属白烧。
        Page<Order> page = new Page<>(1, size, false);
        page.addOrder(OrderItem.asc("create_time"));
        // 加 id 作为第二排序键：create_time 相同时结果稳定，分片之间不会重复/漏处理
        page.addOrder(OrderItem.asc("id"));
        Page<Order> result = lambdaQuery()
                .eq(Order::getStatus, OrderStatus.NO_PAY.getValue())
                .lt(Order::getCreateTime, deadline)
                // ★ keyset 分页：只取排在游标之后的记录。
                //   为什么不用 OFFSET：批次之间会有关单动作把记录移出结果集，
                //   OFFSET 的「第 N 页」就跟着漂了，会跳行。而 (create_time, id) 是单调的，
                //   无论前面的记录怎么变，游标之后的数据都不会被漏掉或重复。
                //   同时它保证每批都严格向前推进，不会因为队头有关不掉的单而原地打转。
                .and(after != null, w -> w.gt(Order::getCreateTime, after.getCreateTime())
                        .or(q -> q.eq(Order::getCreateTime, after.getCreateTime())
                                .gt(Order::getId, after.getId())))
                // 与 pay 侧补偿任务同一套分片写法：按 id 取模，而不是把分片号当页码用
                // （页码会随数据增删漂移，导致各分片重复处理或漏处理）
                .apply(shardTotal > 1, "MOD(id, {0}) = {1}", shardTotal, shardIndex)
                .page(page);
        return result.getRecords();
    }

    @Override
    public List<Order> queryFinishableOrders(LocalDateTime deadline, Order after,
                                             int shardIndex, int shardTotal, int size) {
        // searchCount = false：只要前 N 条，不需要总数，避免每轮多跑一次全索引 COUNT
        Page<Order> page = new Page<>(1, size, false);
        page.addOrder(OrderItem.asc("pay_time"));
        page.addOrder(OrderItem.asc("id"));
        Page<Order> result = lambdaQuery()
                .eq(Order::getStatus, OrderStatus.PAYED.getValue())
                // pay_time 非空才可能算出售后期。status=2 的订单理论上一定有 pay_time，
                // 但历史脏数据里可能有空值，NULL 在 ASC 排序里排最前、又永远不满足 lt(deadline)，
                // 会一直占着队头。显式排除掉，免得它们卡住游标。
                .isNotNull(Order::getPayTime)
                .lt(Order::getPayTime, deadline)
                // keyset 分页：与超时关单同一套理由，OFFSET 在批次之间状态变化时会跳行
                .and(after != null, w -> w.gt(Order::getPayTime, after.getPayTime())
                        .or(q -> q.eq(Order::getPayTime, after.getPayTime())
                                .gt(Order::getId, after.getId())))
                .apply(shardTotal > 1, "MOD(id, {0}) = {1}", shardTotal, shardIndex)
                .page(page);
        return result.getRecords();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean finishOrderInternal(Order order) {
        // 1.条件更新订单：只有「此刻仍是已支付」才允许改成已完成。
        //   不带这个条件的话，用户恰好在任务执行的瞬间提交了退款申请（订单被改成「申请退款」），
        //   任务会拿读到的旧快照把它覆盖回「已完成」，退款单直接消失、钱退不出去。
        //   影响 0 行 = 这一条已经被别人改过，当作跳过处理，不抛异常。
        LocalDateTime finishTime = order.getPayTime()
                .plusHours(tradeProperties.getOrderFinishHours());
        boolean updated = lambdaUpdate()
                .set(Order::getStatus, OrderStatus.FINISHED.getValue())
                .set(Order::getFinishTime, finishTime)
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, OrderStatus.PAYED.getValue())
                .update();
        if (!updated) {
            log.info("订单{}状态已被变更，跳过自动完成", order.getId());
            return false;
        }
        // 2.明细同步置为已完成。
        //   不能复用 detailService.updateStatusByOrderId：那个方法是无条件按 orderId 更新，
        //   会把正在退款（status=6）的明细一并覆盖成已完成，退款流程就断了。
        //   这里同样只动「已支付」的明细。
        detailService.lambdaUpdate()
                .set(OrderDetail::getStatus, OrderStatus.FINISHED.getValue())
                .eq(OrderDetail::getOrderId, order.getId())
                .eq(OrderDetail::getStatus, OrderStatus.PAYED.getValue())
                .update();
        return true;
    }

    @Override
    public void deleteOrder(Long id) {
        // 1.获取登录用户
        Long userId = UserContext.getUserId();
        // 2.查询订单
        Order order = getById(id);
        if (order == null) {
            return;
        }
        // 3.判断订单所属用户与当前登录用户是否一致
        if (!userId.equals(order.getUserId())) {
            // 不一致，说明不是当前用户的订单，结束
            throw new BadRequestException("不能删除他人订单");
        }
        // 4.删除订单
        boolean success = removeById(id);
        if (!success) {
            throw new DbException(OPERATE_FAILED);
        }
    }

    @Override
    public PageDTO<OrderPageVO> queryMyOrderPage(OrderPageQuery pageQuery) {
        Long userId = UserContext.getUserId();
        // 1.分页排序条件
        Page<Order> p = pageQuery.toMpPageDefaultSortByCreateTimeDesc();
        // 2.分页查询订单
        Integer status = pageQuery.getStatus();
        Page<Order> page = lambdaQuery()
                .eq(status != null, Order::getStatus, status)
                .eq(Order::getUserId, userId)
                .page(p);
        // 3.数据判断
        List<Order> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(p);
        }
        // 4.查询订单明细信息
        List<Long> orderIds = records.stream().map(Order::getId).collect(Collectors.toList());
        // 4.1.根据订单id查询订单明细
        List<OrderDetail> details = detailService.queryByOrderIds(orderIds);
        // 4.2.将订单明细分组，key是订单id，值是订单下的所有detail
        Map<Long, List<OrderDetailVO>> detailMap = details.stream()
                .map(od -> BeanUtils.copyBean(od, OrderDetailVO.class))
                .collect(Collectors.groupingBy(OrderDetailVO::getOrderId));
        // 5.转换VO
        List<OrderPageVO> list = new ArrayList<>(orderIds.size());
        for (Order record : records) {
            // 5.1.转换订单
            OrderPageVO v = BeanUtils.toBean(record, OrderPageVO.class);
            // 5.1.1.是否还能支付，必须按「当前时间」实时算，不能只看 status。
            //   关单是异步任务，status 会滞后于时间判断：超时之后、任务关单之前，
            //   订单还是 status=1，但 applyPayOrder 已经会拒绝支付。
            //   前端如果只按 status 显示「去支付」，用户点进去就会看到「订单已超时」。
            //   这里用与 applyPayOrder 的拒绝条件完全互补的写法（!isBefore），
            //   保证两边不会出现「一个说能付、一个说不能付」。
            v.setCanPay(OrderStatus.NO_PAY.equalsValue(record.getStatus())
                    && !record.getCreateTime()
                            .plusMinutes(tradeProperties.getPayOrderTTLMinutes())
                            .isBefore(LocalDateTime.now()));
            list.add(v);
            // 5.2.写入vo
            v.setDetails(detailMap.get(record.getId()));
            v.setStatusDesc(OrderStatus.desc(v.getStatus()));
        }
        return PageDTO.of(page, list);
    }

    @Override
    public OrderVO queryOrderById(Long id) {
        // 1.查询订单
        Order order = getById(id);
        if (order == null) {
            throw new BadRequestException(ORDER_NOT_EXISTS);
        }
        // 2.查询订单详情
        List<OrderDetail> details = detailService.queryByOrderId(id);
        // 3.转换VO
        // 3.1.订单
        OrderVO vo = BeanUtils.toBean(order, OrderVO.class);
        // 3.2.订单详情
        List<OrderDetailVO> dvs = BeanUtils.copyList(details, OrderDetailVO.class, (d, v) -> v.setCanRefund(
                // 订单已经支付，且 退款没有在进行中，标记为可退款状态
                OrderStatus.canRefund(d.getStatus()) && !RefundStatus.inProgress(v.getRefundStatus())
        ));
        vo.setDetails(dvs);
        // 3.3.订单进度
        vo.setProgressNodes(detailService.packageProgressNodes(order, null));
        // 3.4.优惠卷规则
        List<String> rules = Collections.emptyList();
        if (CollUtils.isNotEmpty(order.getCouponIds())) {
            rules = promotionClient.queryCouponRules(order.getCouponIds());
        }
        vo.setCouponDesc(String.join("/", rules));

        return vo;
    }

    @Override
    public PlaceOrderResultVO queryOrderStatus(Long orderId) {
        // 1.查询订单
        Order order = getById(orderId);
        if (order == null) {
            throw new BizIllegalException(ORDER_NOT_EXISTS);
        }
        // 1.1.待支付时主动向支付中心同步一次。
        // 原实现只读本地 order 表，而本地表只有两条更新途径：
        //   ① 下单时投递的延迟查单消息（5s/15s/30s/60s/120s，用尽即止）
        //   ② XXL-JOB 补偿任务（120s 一轮，且只扫 pay_order.status=待支付）
        // 于是「用户付完款 → 刷新页面 / 前端 5s 轮询」这条最常见的路径拿到的永远是本地旧值，
        // 实测付款后要等 40s~2min 才翻状态。这里主动查一次，命中已支付就走与 MQ 通知
        // 完全相同的处理逻辑（handlePaySuccess 内部有条件更新幂等，重复调用安全）。
        if (OrderStatus.NO_PAY.equalsValue(order.getStatus())) {
            order = syncFromPayCenter(order);
        }
        // 2.计算超时时间
        LocalDateTime outTime = null;
        if (OrderStatus.NO_PAY.equalsValue(order.getStatus())) {
            outTime = order.getCreateTime().plusMinutes(tradeProperties.getPayOrderTTLMinutes());
        }
        // 3.封装结果
        return PlaceOrderResultVO.builder()
                .orderId(orderId)
                .payAmount(order.getRealAmount())
                .status(order.getStatus())
                .payOutTime(outTime)
                .build();
    }

    /**
     * 主动向支付中心同步一次订单支付状态。
     * <p>
     * 任何异常都只记日志并返回原订单：支付中心抖动不能让「查订单状态」这个基础接口跟着报错。
     * 查单频率由 pay 侧控制（同一支付单 3s 内只真查一次支付宝），这里不做重复节流。
     * </p>
     */
    private Order syncFromPayCenter(Order order) {
        PayResultDTO payResult;
        try {
            payResult = payClient.queryPayResult(order.getId());
        } catch (Exception e) {
            log.warn("主动查询订单{}支付状态失败：{}", order.getId(), e.getMessage());
            return order;
        }
        if (payResult == null || PayResultDTO.SUCCESS != payResult.getStatus()) {
            return order;
        }
        try {
            // 走代理调用，保证 handlePaySuccess 上的 @Transactional 生效
            selfProvider.getObject().handlePaySuccess(payResult);
        } catch (Exception e) {
            log.error("同步订单{}支付成功状态失败", order.getId(), e);
            return order;
        }
        Order latest = getById(order.getId());
        return latest != null ? latest : order;
    }

    @Override
    @Transactional
    public void handlePaySuccess(PayResultDTO payResult) {
        // 0.防御：bizOrderId 缺失时必须报错而不是静默返回。
        // 之前 pay 侧 queryPayResult 漏回传 bizOrderId，这里 getById(null) 查不到订单就悄悄 return，
        // 结果是「pay 已支付、trade 永远待支付」，日志里只有一句 Parameters: null，极难定位。
        if (payResult.getBizOrderId() == null) {
            log.error("支付成功通知缺少业务订单号 bizOrderId，已忽略：{}", payResult);
            return;
        }
        // 1.查询订单
        Order order = getById(payResult.getBizOrderId());
        if (order == null) {
            log.error("支付成功通知对应的订单不存在，orderId={}，通知内容：{}",
                    payResult.getBizOrderId(), payResult);
            return;
        }
        // 2.更新订单状态
        // 条件更新：只有「待支付」才能迁移到「已支付」。
        // 「支付成功」现在有两条来源 —— pay 查单命中后主动发 MQ、XXL-JOB 补偿任务兜底重发；
        // 不加这个条件的话同一笔订单会被处理两次，最直接的后果是第 5 步报名消息重复发送，
        // learning 侧出现重复报名记录。条件更新保证只有第一次能成功，后续直接返回。
        boolean updated = lambdaUpdate()
                .set(Order::getStatus, OrderStatus.PAYED.getValue())
                .set(Order::getPayTime, payResult.getSuccessTime())
                .set(Order::getPayChannel, payResult.getPayChannel())
                .set(Order::getPayOrderNo, payResult.getPayOrderNo())
                .set(Order::getMessage, "用户支付成功")
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, OrderStatus.NO_PAY.getValue())
                .update();
        if (!updated) {
            log.info("订单{}已处理过支付成功，跳过重复通知", order.getId());
            return;
        }
        // 3.更新订单条目
        detailService.markDetailSuccessByOrderId(order.getId(), payResult.getPayChannel(), payResult.getSuccessTime());
        // 4.查询订单包含的课程信息
        List<Long> cIds = detailService.queryCourseIdsByOrderId(order.getId());
        // 5.发送MQ消息，通知报名成功
        rabbitMqHelper.send(
                MqConstants.Exchange.ORDER_EXCHANGE,
                MqConstants.Key.ORDER_PAY_KEY,
                OrderBasicDTO.builder()
                        .orderId(order.getId()).userId(order.getUserId()).courseIds(cIds)
                        .finishTime(payResult.getSuccessTime())
                        .build()
        );
    }


}