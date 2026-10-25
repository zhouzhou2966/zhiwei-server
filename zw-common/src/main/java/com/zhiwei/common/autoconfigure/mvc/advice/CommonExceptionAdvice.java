package com.zhiwei.common.autoconfigure.mvc.advice;

import com.zhiwei.common.constants.Constant;
import com.zhiwei.common.domain.R;
import com.zhiwei.common.exceptions.CommonException;
import com.zhiwei.common.exceptions.DbException;
import com.zhiwei.common.utils.WebUtils;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
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

    /*
     * 下面四个是 Spring MVC 在「请求进不到 Controller 方法体」时抛的异常。
     *
     * 它们都是普通 Exception，会被最后一个 @ExceptionHandler(Exception.class) 兜底吃掉，
     * 于是统一变成 500「服务器内部异常」。问题在于：
     *   - 明明是用 POST 打了 @GetMapping 的接口（405）、少传了必填参数（400）、
     *     JSON 写坏了（400）、?pageNo=abc 类型不对（400），客户端却是「服务端炸了」的观感；
     *   - 监控上这些全算 5xx，会污染错误率告警；
     *   - 排查时得先去日志翻「其他异常 uri」下面那一行异常栈才知道到底是谁的错。
     * 2026-10-25 冒烟时就有一连串 500 其实全是 MissingServletRequestParameterException
     * （漏传 bizIds / ids / types / couponIds），白查了半天。
     *
     * 注意：这不是 Boot 3 升级带来的回退，Boot 2.7 时代同样是 500 —— 只是一直没人管。
     */

    /**
     * 请求方法不支持，例如用 POST 打 {@code @GetMapping} 的接口。按 405 返回。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Object handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持 uri : {} -> {}", WebUtils.getRequest().getRequestURI(), e.getMessage());
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        String msg = supported == null || supported.isEmpty()
                ? "请求方法不支持：" + e.getMethod()
                : "请求方法不支持：" + e.getMethod() + "，该接口支持 " + supported;
        return processResponse(HttpStatus.METHOD_NOT_ALLOWED.value(), HttpStatus.METHOD_NOT_ALLOWED.value(), msg);
    }

    /**
     * 少了必填的 query / form 参数，例如 {@code /likes/list} 忘了传 {@code bizIds}。按 400 返回。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Object handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数 uri : {} -> {}", WebUtils.getRequest().getRequestURI(), e.getMessage());
        return processResponse(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.value(),
                "缺少请求参数：" + e.getParameterName());
    }

    /**
     * 参数类型对不上，例如 {@code ?pageNo=abc} 却声明成 Integer。按 400 返回。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Object handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配 uri : {} -> {}", WebUtils.getRequest().getRequestURI(), e.getMessage());
        String required = e.getRequiredType() == null ? "?" : e.getRequiredType().getSimpleName();
        return processResponse(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.value(),
                "参数类型不匹配：" + e.getName() + " 应为 " + required);
    }

    /**
     * 请求体读不出来，例如 JSON 少了引号、字段类型写错。按 400 返回。
     *
     * <p>刻意不把 {@code e.getMessage()} 原样返回给前端 —— 里面会带出实体类的全限定名和
     * 字段路径，属于内部结构泄露。日志里记全量，响应只给一句人话。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败 uri : {} -> {}", WebUtils.getRequest().getRequestURI(), e.getMessage());
        return processResponse(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.value(), "请求体格式错误");
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
