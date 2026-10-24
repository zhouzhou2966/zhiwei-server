package com.zhiwei.user.controller;

import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.user.domain.dto.StudentFormDTO;
import com.zhiwei.user.service.ICodeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 短信验证码接口。
 *
 * <p>为什么单独放一个 controller 而不是塞进 StudentController：
 * 验证码是「换绑手机 / 注册 / 忘记密码」等多个流程共用的前置能力，
 * 不归属于某个具体资源，独立出来调用方语义更清楚。
 *
 * <p>路径是 {@code /code}，网关对 {@code /us/**} 有 StripPrefix=1，
 * 所以前端要写成 {@code /us/code}。
 */
@RestController
@RequestMapping("/code")
@Tag(name = "验证码接口")
public class CodeController {

    @Autowired
    private ICodeService codeService;

    /**
     * 演示环境「回显验证码」开关，配置在 bootstrap-dev.yml / bootstrap-local.yml。
     *
     * <p>教程里的短信通道 AK 已被清空，验证码根本发不到真机上，
     * 不回显的话整条「换绑手机」流程无法演示。生产环境必须保持 false。
     */
    @Value("${zw.sms.echo-code:false}")
    private boolean echoCode;

    @Operation(summary = "发送手机验证码")
    @PostMapping
    public String sendVerifyCode(HttpServletRequest request, @RequestBody StudentFormDTO formDTO) {
        String code = codeService.sendVerifyCode(formDTO.getCellPhone(), resolveClientIp(request));
        // 只在演示环境把验证码带回去；生产返回 null，响应体里不会出现 data 字段
        return echoCode ? code : null;
    }

    /**
     * 解析客户端真实 IP，供 IP 维度限流使用。
     *
     * <p>顺序不能颠倒：
     * ① {@code X-Real-IP} —— nginx 用 {@code $remote_addr} 覆盖写入，客户端伪造不了，最可信；
     * ② {@code X-Forwarded-For} 最左侧 —— 本机直连网关（链路上没有 nginx）时的兜底；
     * ③ {@code getRemoteAddr()} —— 最后兜底。
     *
     * <p>刻意<b>不</b>取 XFF 最右侧：那是网关/nginx 容器自己的地址，
     * 所有请求取值都相同，拿它做限流等于全站共用一个配额，比不限流更糟。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.isNotBlank(realIp)) {
            return realIp.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(forwarded)) {
            // XFF 是「客户端, 代理1, 代理2」的链路串，最左侧才是最初的客户端
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
