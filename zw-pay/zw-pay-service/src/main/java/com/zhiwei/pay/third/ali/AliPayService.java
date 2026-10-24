package com.zhiwei.pay.third.ali;

import com.alipay.easysdk.factory.Factory;
import com.alipay.easysdk.kernel.util.ResponseChecker;
import com.alipay.easysdk.payment.common.models.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.easysdk.payment.common.models.AlipayTradeCloseResponse;
import com.alipay.easysdk.payment.common.models.AlipayTradeQueryResponse;
import com.alipay.easysdk.payment.common.models.AlipayTradeRefundResponse;
import com.alipay.easysdk.payment.common.models.TradeFundBill;
import com.alipay.easysdk.payment.facetoface.models.AlipayTradePrecreateResponse;
import com.zhiwei.common.exceptions.CommonException;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.DateUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.pay.sdk.constants.PayConstants;
import com.zhiwei.pay.third.CommonPayProperties;
import com.zhiwei.pay.third.IPayService;
import com.zhiwei.pay.third.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import static com.zhiwei.pay.sdk.constants.PayConstants.ALI_CHANNEL_CODE;

@Slf4j
@Service(ALI_CHANNEL_CODE)
@RequiredArgsConstructor
public class AliPayService implements IPayService {

    private final CommonPayProperties commonPayProperties;

    @Override
    public PrepayResponse createPrepayOrder(String title, String orderNo, Integer amount) {
        // 1.动态获取回调地址
        String notifyUrl = commonPayProperties.getNotifyHost() + "/notify/" + PayConstants.ALI_CHANNEL_CODE;

        // 2. 发起API调用（以创建当面付收款二维码为例）
        AlipayTradePrecreateResponse response = null;
        try {
            response = Factory.Payment.FaceToFace()
                    .asyncNotify(notifyUrl)
                    .preCreate(title, orderNo, transferAmount2String(amount));
        } catch (Exception e) {
            log.error("支付宝预下单失败，订单id：{}", orderNo, e);
            throw new CommonException("支付宝预下单失败", e);
        }
        // 3.处理响应
        PrepayResponse.PrepayResponseBuilder builder = PrepayResponse.builder();
        if (ResponseChecker.success(response)) {
            // 3.1.响应结果正常
            builder.success(true).payUrl(response.getQrCode());
        } else {
            // 3.2.响应结果异常
            builder.success(false).code(response.getCode()).msg(response.getMsg());
        }
        return builder.build();
    }



    @Override
    public PayStatusResponse queryPayOrderStatus(String payOrderNo) {
        // 1.发起请求
        AlipayTradeQueryResponse response = null;
        try {
            response = Factory.Payment.Common().query(payOrderNo);
        } catch (Exception e) {
            log.error("支付宝查询支付单状态失败，订单id：{}", payOrderNo, e);
            throw new CommonException("支付宝查询支付单状态失败", e);
        }
        // 2.解析
        if (!ResponseChecker.success(response)) {
            // 2.1.响应结果异常
            // code 只表示「网关层有没有通」，业务失败时它恒为 40004（Business Failed），
            // 真正的错误码在 subCode 里：ACQ.TRADE_NOT_EXIST（交易不存在）、
            // ACQ.TRADE_STATUS_ERROR（当前状态不支持该操作，交易已支付/已关闭都是它）。
            // 上层 canCloseOnTimeout 要靠这个码区分「渠道侧根本没有这笔单」和「交易已支付」，
            // 这里不透出 subCode，那边拿 40004 去比对就恒为 false，僵尸单放行逻辑等于死代码。
            String code = StringUtils.isNotBlank(response.getSubCode())
                    ? response.getSubCode() : response.getCode();
            String msg = StringUtils.isNotBlank(response.getSubMsg())
                    ? response.getSubMsg() : response.getMsg();
            return PayStatusResponse.builder().success(false).code(code).msg(msg).build();
        }
        // 2.2.响应结果正常
        String success_time = response.getSendPayDate();
        LocalDateTime successTime = StringUtils.isBlank(success_time) ?
                LocalDateTime.now() : DateUtils.parse(success_time, DateUtils.DEFAULT_DATE_TIME_FORMAT);
        return PayStatusResponse.builder().success(true)
                        .payStatus(PayStatus.valueOf(response.getTradeStatus()).getValue())
                        .payOrderNo(response.getOutTradeNo())
                        .totalAmount(transferStringAmount2Int(response.getTotalAmount()))
                        .successTime(successTime)
                        .build();
    }

    @Override
    public boolean closeOrder(String payOrderNo) {
        AlipayTradeCloseResponse response;
        try {
            response = Factory.Payment.Common().close(payOrderNo);
        } catch (Exception e) {
            log.error("支付宝关闭交易失败，订单id：{}", payOrderNo, e);
            return false;
        }
        if (ResponseChecker.success(response)) {
            return true;
        }
        // 关单被拒绝时一律返回 false，交给调用方查单判断真实状态。
        // 支付宝在「交易已支付」「交易已关闭」「交易不存在」等情况下都会拒绝关单，
        // 错误码区分度不高（ACQ.TRADE_STATUS_ERROR 既可能是已支付也可能是已关闭），
        // 靠错误码猜一旦把「已支付」当成「已关闭」，就是用户付了钱而订单被关掉的资损。
        log.warn("支付宝关闭交易未成功，订单id：{}，code：{}，msg：{}",
                payOrderNo, response.getSubCode(), response.getSubMsg());
        return false;
    }

    @Override
    public RefundResponse refundOrder(String payOrderNo, String refundOrderNo, Integer refundAmount, Integer totalAmount) {
        // 1.发起请求
        AlipayTradeRefundResponse response = null;
        try {
            response = Factory.Payment.Common()
                    .optional("query_options", List.of("refund_detail_item_list"))
                    .optional("out_request_no", refundOrderNo)
                    .refund(payOrderNo, transferAmount2String(refundAmount));
        } catch (Exception e) {
            log.error("支付宝申请退款失败，订单id：{} ，退款订单id：{}", payOrderNo, refundOrderNo, e);
            throw new CommonException("支付宝申请退款失败", e);
        }
        // 2.解析响应
        if (!ResponseChecker.success(response)) {
            // 2.1.响应结果异常
            return RefundResponse.builder().success(false).code(response.getSubCode()).msg(response.getSubMsg()).build();
        }
        // 2.2.响应结果正常，获取响应细节数据
        List<TradeFundBill> refundDetailItemList = response.getRefundDetailItemList();
        boolean hasDetail = CollUtils.isEmpty(refundDetailItemList);
        // 2.3.获取退款成功标示
        boolean success = StringUtils.equals(response.getFundChange(), "Y");
        return RefundResponse.builder()
                .success(true)
                .status(success ? RefundStatus.SUCCESS.getValue(): RefundStatus.UN_KNOWN.getValue())
                .channel(hasDetail ? null : refundDetailItemList.get(0).fundChannel)
                .amount(hasDetail ? null : transferStringAmount2Int(refundDetailItemList.get(0).getAmount()))
                .build();
    }

    @Override
    public RefundResponse queryRefundStatus(String orderNo, String refundOrderNo) {
        // 1.发起请求
        AlipayTradeFastpayRefundQueryResponse response = null;
        try {
            response = Factory.Payment.Common().queryRefund(orderNo, refundOrderNo);
        } catch (Exception e) {
            log.error("支付宝查询退款单状态失败，订单id：{}", orderNo, e);
            throw new CommonException("支付宝查询退款单状态失败", e);
        }
        // 2.解析
        if (!ResponseChecker.success(response)) {
            // 2.1.查询失败（交易不存在、参数错误等），按失败返回，交给上层决定是否重试
            return RefundResponse.builder().success(false).code(response.getCode()).msg(response.getMsg()).build();
        }
        // 2.2.查询成功，解析退款状态
        String refundStatus = response.getRefundStatus();
        boolean refundSuccess = "REFUND_SUCCESS".equals(refundStatus);
        // 退款明细可能为空（例如退款刚提交还没生成资金明细），直接取 get(0) 会 NPE
        List<TradeFundBill> details = response.getRefundDetailItemList();
        boolean hasDetail = CollUtils.isEmpty(details);
        return RefundResponse.builder()
                .success(true)
                .status(refundSuccess ? RefundStatus.SUCCESS.getValue() : RefundStatus.UN_KNOWN.getValue())
                .channel(refundSuccess && !hasDetail ? details.get(0).fundChannel : null)
                .amount(refundSuccess && !hasDetail ? transferStringAmount2Int(details.get(0).getAmount()) : 0)
                .build();
    }


    public static int transferStringAmount2Int(String totalAmount) {
        return new BigDecimal(totalAmount).multiply(BigDecimal.valueOf(100)).intValue();
    }

    /**
     * 分 -> 元 字符串。
     *
     * 原来用的是 {@code divide(new BigDecimal(100), new MathContext(2, HALF_UP))}，
     * 而 MathContext(2) 表示「2 位有效数字」而不是「2 位小数」，导致：
     * 9999 分 -> 1.0E+2、12345 分 -> 1.2E+2、19999 分 -> 2.0E+2。
     * 支付宝收到科学计数法直接报 40004 Business Failed，等于 99.99 元以上的订单一律下不了单，
     * 退款金额走的是同一个方法，同样中招。这里改成固定 2 位小数并输出普通十进制字符串。
     */
    public static String transferAmount2String(Integer amount) {
        return BigDecimal.valueOf(amount)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
