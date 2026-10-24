package com.zhiwei.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.api.dto.course.CoursePurchaseInfoDTO;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.trade.domain.po.Order;
import com.zhiwei.trade.domain.po.OrderDetail;
import com.zhiwei.trade.domain.po.RefundApply;
import com.zhiwei.trade.domain.query.OrderDetailPageQuery;
import com.zhiwei.trade.domain.vo.OrderDetailAdminVO;
import com.zhiwei.trade.domain.vo.OrderDetailPageVO;
import com.zhiwei.trade.domain.vo.OrderProgressNodeVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 订单明细 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-29
 */
public interface IOrderDetailService extends IService<OrderDetail> {

    /**
     * 按订单id批量更新订单明细的状态
     * <p>
     * 为什么返回 boolean 而不是抛异常：关单流程（{@code OrderServiceImpl#closeOrderInternal}）里，
     * 明细更新只是收尾动作 —— 此时第三方渠道的支付单已经关闭、本地订单也已经置为已关闭，
     * 这些动作都不可回退。若明细缺失就抛异常，整笔事务回滚，订单状态会退回「待支付」，
     * 结果变成「渠道已关 + 本地待支付」，下一轮任务重新扫到它、再失败一次，
     * 连续失败触发熔断后，排在它后面的所有超时订单全部堵死。
     * 所以这里只回报成败，由调用方决定是记日志还是中断流程。
     * </p>
     *
     * @param orderId 订单id
     * @param status  要更新成的新状态
     * @return true=至少更新了一行；false=该订单没有明细，一行都没更新
     */
    boolean updateStatusByOrderId(Long orderId, Integer status);

    List<OrderDetail> queryByOrderIds(List<Long> orderIds);

    List<OrderDetail> queryByOrderId(Long orderId);

    PageDTO<OrderDetailPageVO> queryDetailForPage(OrderDetailPageQuery pageQuery);

    OrderDetailAdminVO queryOrdersDetailProgress(Long id);

    List<OrderProgressNodeVO> packageProgressNodes(Order order, RefundApply refundApply);

    void markDetailSuccessByOrderId(Long id, String payChannel, LocalDateTime successTime);

    void updateRefundStatusById(Long orderDetailId, int status);

    List<Long> queryCourseIdsByOrderId(Long orderId);

    Boolean checkCourseOrderInfo(Long courseId);

    Map<Long, Integer> countEnrollNumOfCourse(List<Long> courseIdList);

    Map<Long, Integer> countEnrollCourseOfStudent(List<Long> studentIds);

    CoursePurchaseInfoDTO getPurchaseInfoOfCourse(Long courseId);
}
