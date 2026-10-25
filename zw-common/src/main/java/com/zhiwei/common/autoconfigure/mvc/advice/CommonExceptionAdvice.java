package com.zhiwei.common.autoconfigure.mvc.advice;

import com.zhiwei.common.constants.Constant;
import com.zhiwei.common.domain.R;
import com.zhiwei.common.exceptions.CommonException;
import com.zhiwei.common.exceptions.DbException;
import com.zhiwei.common.utils.WebUtils;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class CommonExceptionAdvice {

    @ExceptionHandler(DbException.class)
    public Object handleDbException(DbException e) {
        log.error("mysql数据库操作异常 -> ", e);
        return processResponse(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler(CommonException.class)
    public Object handleBadRequestException(CommonException e) {
        log.error("自定义异常 -> {} , 状态码：{}, 异常原因：{}  ",e.getClass().getName(), e.getStatus(), e.getMessage());
        log.debug("", e);
        return processResponse(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler(FeignException.class)
    public Object handleFeignException(FeignException e) {
        log.error("feign远程调用异常 -> ", e);
        return processResponse(e.status(), e.status(), e.contentUTF8());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors()
                .stream().map(ObjectError::getDefaultMessage)
                .collect(Collectors.joining("|"));
        log.error("请求参数校验异常 -> {}", msg);
        log.debug("", e);
        return processResponse(400, 400, msg);
    }
    @ExceptionHandler(BindException.class)
    public Object handleBindException(BindException e) {
        log.error("请求参数绑定异常 ->BindException， {}", e.getMessage());
        log.debug("", e);
        return processResponse(400, 400, "请求参数格式错误");
    }

    /*
     * 这里原本有一个 @ExceptionHandler(NestedServletException.class) 统一返回 400「请求参数异常」，已删除。
     *
     * 原因：NestedServletException 是 DispatcherServlet 的通用包装，里面装的可能是任意异常，甚至 Error。
     * 2026-10-21 就有一个 NoClassDefFoundError（zw-common jar 被原地覆盖导致懒加载失败）被它包装成
     * 400「请求参数异常」，看起来跟「客户端参数传错了」一模一样：监控看不到 5xx、客户端以为是自己错
     * 不会重试、排查被带偏几个小时。
     *
     * 真正的参数校验异常（MethodArgumentNotValidException / BindException / ConstraintViolationException）
     * 都有各自的分支，会优先匹配，走不到这里。所以兜底交给下面的 Exception 分支返回 500 才是对的。
     */

    @ExceptionHandler(ConstraintViolationException.class)
    public Object handViolationException(ConstraintViolationException e) {
        log.error("请求参数异常 -> ConstraintViolationException, {}", e.getMessage());

        return processResponse( HttpStatus.OK.value(), HttpStatus.BAD_REQUEST.value(),
                e.getConstraintViolations().stream().map(ConstraintViolation::getMessage).distinct().collect(Collectors.joining("|"))
                );
    }

    /**
     * 请求了一个没有对应 handler 的路径。
     *
     * Spring 6.1 起，这类请求不再走 BasicErrorController，而是由 ResourceHttpRequestHandler
     * 抛 NoResourceFoundException（它 extends ServletException，是普通 Exception），
     * 于是会被下面的 {@code @ExceptionHandler(Exception.class)} 兜底接住，返回 500「服务器内部异常」。
     * Boot 2.7 / Spring 5.3 时代这类请求走 BasicErrorController，不经过 @ControllerAdvice，所以是 404。
     *
     * 结果就是升级后「接口不存在」看起来像「服务炸了」：监控上是一堆 5xx、客户端以为该重试、
     * 排查时先去翻日志找异常栈。这里单独接住，按 404 语义返回，别让兜底分支吃掉。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNoResourceFound(NoResourceFoundException e) {
        log.warn("请求路径不存在 uri : {}", WebUtils.getRequest().getRequestURI());
        return processResponse(HttpStatus.NOT_FOUND.value(), HttpStatus.NOT_FOUND.value(), "请求路径不存在");
    }

    @ExceptionHandler(Exception.class)
    public Object handleRuntimeException(Exception e) {
        log.error("其他异常 uri : {} -> ", WebUtils.getRequest().getRequestURI(), e);
        return processResponse(500, 500, "服务器内部异常");
    }

    private Object processResponse(int status, int code, String msg){
        // 1.标记响应异常已处理（避免重复处理）
        WebUtils.setResponseHeader(Constant.BODY_PROCESSED_MARK_HEADER, "true");
        // 2.如果是网关请求，http状态码修改为200返回，前端基于业务状态码code来判断状态
        // 如果是微服务请求，http状态码基于异常原样返回，微服务自己做fallback处理
        return WebUtils.isGatewayRequest() ?
                R.error(code, msg).requestId(MDC.get(Constant.REQUEST_ID_HEADER))
                : ResponseEntity.status(status).body(msg);
    }
}
