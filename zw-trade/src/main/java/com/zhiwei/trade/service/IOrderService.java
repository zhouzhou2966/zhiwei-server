package com.zhiwei.trade.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.pay.sdk.dto.PayResultDTO;
import com.zhiwei.trade.domain.dto.PlaceOrderDTO;
import com.zhiwei.trade.domain.po.Order;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.trade.domain.po.OrderDetail;
import com.zhiwei.trade.domain.query.OrderPageQuery;
import com.zhiwei.trade.domain.vo.OrderConfirmVO;
import com.zhiwei.trade.domain.vo.OrderPageVO;
import com.zhiwei.trade.domain.vo.OrderVO;
import com.zhiwei.trade.domain.vo.PlaceOrderResultVO;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 订单 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-29
 */
public interface IOrderService extends IService<Order> {

    PlaceOrderResultVO placeOrder(PlaceOrderDTO placeOrderDTO);

    @Transactional
    void saveOrderAndDetails(Order order, List<OrderDetail> orderDetails);

    void cancelOrder(Long orderId);

    /**
     * 关闭一笔「待支付」订单（内部公共逻辑，用户主动取消和定时任务超时关单共用）。
     * <p>
     * 顺序固定为：先让 pay 侧关掉第三方渠道 → 渠道确认不可支付后再关本地。
     * 反过来的话用户手里的二维码仍然能付款，回调过来本地的条件更新（eq status = 待支付）会失败并静默跳过，
     * 结果是「钱收了、订单已关闭、用户没课」。
     * </p>
     *
     * @param order  要关闭的订单，必须已从库里查出（方法内会再判断一次状态做幂等）
     * @param reason 关闭原因，会写进 order.message 展示给用户
     * @return true 表示本次真正关掉了；false 表示跳过（已支付 / 已关闭 / 支付单暂时关不掉）
     */
    boolean closeOrderInternal(Order order, String reason);

    /**
     * 查询已超时但仍待支付的订单，供定时任务分片扫描。
     *
     * @param deadline   创建时间早于该时间视为超时
     * @param after      游标：只取排在它之后的订单，首次调用传 null。
     *                   传的是上一批的最后一条，用 {@code (create_time, id)} 做 keyset 分页，
     *                   不用 {@code OFFSET} —— OFFSET 在批次之间有关单动作时会导致跳行或重复
     * @param shardIndex 当前分片号，从 0 开始
     * @param shardTotal 分片总数，为 1 时不分片
     * @param size       单次最多取多少条
     * @return 超时未支付订单，按创建时间升序
     */
    List<Order> queryTimeoutNoPayOrders(LocalDateTime deadline, Order after,
                                        int shardIndex, int shardTotal, int size);

    /**
     * 查询「已支付且售后期已过」的订单，供定时任务分片扫描后置为已完成。
     * <p>
     * 与 {@link #queryTimeoutNoPayOrders} 的写法刻意保持一致：keyset 分页 + id 取模分片。
     * 排序键换成 {@code (pay_time, id)} —— 售后期是从<b>支付时刻</b>算的，
     * 不是从下单时刻，所以游标和 deadline 都必须落在 pay_time 上。
     * </p>
     *
     * @param deadline   支付时间早于该时间视为售后期已过
     * @param after      游标：只取排在它之后的订单，首次调用传 null
     * @param shardIndex 当前分片号，从 0 开始
     * @param shardTotal 分片总数，为 1 时不分片
     * @param size       单次最多取多少条
     * @return 待完成订单，按支付时间升序
     */
    List<Order> queryFinishableOrders(LocalDateTime deadline, Order after,
                                      int shardIndex, int shardTotal, int size);

    /**
     * 把一笔售后期已过的订单置为「已完成」，并同步更新订单明细。
     * <p>
     * <b>为什么不是简单的一句 update：</b>
     * 订单和明细都要改，且都必须带「当前仍是已支付」的条件。
     * 用户恰好在任务执行的那一瞬间申请退款时，订单状态会被退款流程改成「申请退款」，
     * 此时任务不能拿读到的旧快照去覆盖它 —— 否则退款单会凭空消失，钱退不出去。
     * 条件更新影响 0 行即视为「这一条被别人改过了」，直接跳过，不报错。
     * </p>
     * <p>
     * <b>finish_time 用 pay_time + N 小时而不是 now()：</b>
     * 任务停过一段时间、补跑时，完成时间仍然落在「支付满 N 小时」那个准确时刻，
     * 不会因为补跑得晚而整体后移，历史数据的完成时间也不会因为执行时机不同而各不相同。
     * </p>
     *
     * @param order 要完成的订单，必须已从库里查出（方法内会再判断一次状态做幂等）
     * @return true 表示本次真正完成了；false 表示跳过（已被退款 / 已被别的实例处理过）
     */
    boolean finishOrderInternal(Order order);

    void deleteOrder(Long id);

    PageDTO<OrderPageVO> queryMyOrderPage(OrderPageQuery pageQuery);

    OrderVO queryOrderById(Long id);

    PlaceOrderResultVO queryOrderStatus(Long orderId);

    void handlePaySuccess(PayResultDTO payResult);

    PlaceOrderResultVO enrolledFreeCourse(Long courseId);

    OrderConfirmVO prePlaceOrder(List<Long> courseIds);

}
