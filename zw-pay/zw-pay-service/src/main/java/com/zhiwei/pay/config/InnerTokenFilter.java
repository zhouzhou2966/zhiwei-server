package com.zhiwei.pay.config;

import com.zhiwei.common.domain.R;
import com.zhiwei.common.utils.JsonUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.pay.sdk.constants.PayConstants;
import com.zhiwei.pay.third.CommonPayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * pay 内部接口的准入校验。
 * <p>
 * <b>要解决的问题</b>：pay 的 {@code /ps/**} 在网关 {@code bootstrap.yml} 里是 {@code Path=/ps/**}
 * 全量转发，而 zw-pay-service 没引 zw-auth-resource-sdk，全链路没有任何身份校验。于是公网上：
 * <ul>
 *   <li>{@code POST /ps/refund-orders} —— 只要知道 bizOrderNo（trade 的自增订单 id，可枚举）就能发起退款，
 *       金额从 pay_order 取，退到用户原路；</li>
 *   <li>{@code POST /ps/pay-channels} / {@code PUT /ps/pay-channels/{id}} —— 能改支付渠道的 appId、商户私钥，
 *       等于能改收款账户；</li>
 *   <li>{@code POST /ps/pay-orders} 能污染支付单，{@code GET /ps/pay-orders/{id}/status} 能查任意订单支付结果。</li>
 * </ul>
 * </p>
 * <p>
 * <b>为什么用共享密钥而不是接 auth sdk</b>：这些接口全部是 trade 通过 Feign 直连调用的内部接口
 * （前端只调 {@code /ts/pay/*}，由 trade 转发），调用方本身没有用户上下文，
 * 接了用户鉴权反而会把内部调用全部挡死。密钥比用户身份更贴合这里的语义。
 * </p>
 * <p>
 * <b>为什么放在 pay 侧而不是只收窄网关路由</b>：网关只解决「公网进不来」，
 * 而 VM 内网的其他容器、以及将来任何误配的路由都能直连 pay:8087。放在 pay 侧才是最终防线，
 * 网关收窄只能算额外一层。两条都做才叫纵深。
 * </p>
 * <p>
 * <b>为什么未配置密钥时返回 500 而不是放行</b>：放行等于「忘记配配置 = 公网裸奔」，
 * 而且失败现象是静默的，很难被发现。这里宁可让 pay 直接不可用，逼着把配置补上。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class InnerTokenFilter extends OncePerRequestFilter {

    private final CommonPayProperties payProperties;

    /**
     * 免校验路径前缀。
     * <ul>
     *   <li>{@code /notify/} —— 支付宝/微信的回调，必须公网可达。它自带验签
     *       （微信 V3 用平台证书验签，支付宝用 RSA 公钥验签），伪造的回调过不了校验，
     *       且回调方是第三方服务器、不可能携带我们的内部密钥。</li>
     *   <li>{@code /actuator/} —— 健康检查与指标，运维和容器探针要用。</li>
     *   <li>swagger 相关 —— 只是接口文档（GET），没有任何写操作；
     *       文档里的「Try it out」发出的请求仍然要过本过滤器，所以放行文档不等于放行接口。
     *       若要彻底关闭，把 {@code zw.swagger.enable} 置为 false 即可。</li>
     * </ul>
     */
    private static final String[] WHITE_LIST = {
            "/notify/",
            "/actuator/",
            "/v2/api-docs",
            "/v3/api-docs",
            "/swagger-resources",
            "/swagger-ui",
            "/webjars/",
            "/doc.html",
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = pathWithoutContext(request);
        if (isWhiteListed(path)) {
            chain.doFilter(request, response);
            return;
        }

        String expected = payProperties.getInnerToken();
        if (StringUtils.isBlank(expected)) {
            log.error("未配置 zw.pay.inner-token，已拒绝内部接口调用 uri={}。" +
                    "请在 Nacos 的 pay-service.yaml 中补上该配置", path);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "支付服务内部密钥未配置");
            return;
        }
        // 不打印真实密钥，只记录是否携带，避免密钥进日志
        if (!expected.equals(request.getHeader(PayConstants.INNER_TOKEN_HEADER))) {
            log.warn("支付内部接口密钥校验失败 uri={}，是否携带 X-Inner-Token：{}",
                    path, request.getHeader(PayConstants.INNER_TOKEN_HEADER) != null);
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "无权调用支付内部接口");
            return;
        }
        chain.doFilter(request, response);
    }

    private String pathWithoutContext(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.isNotBlank(contextPath) && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private boolean isWhiteListed(String path) {
        for (String prefix : WHITE_LIST) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 直接写响应体，不走 {@code CommonExceptionAdvice}：过滤器在 DispatcherServlet 之前，
     * 抛异常也到不了 ControllerAdvice，只能自己拼。格式与 {@code R} 保持一致，
     * 让调用方（Feign）和网关看到的错误结构统一。
     */
    private void writeError(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtils.toJsonStr(R.error(status, msg)));
    }
}
