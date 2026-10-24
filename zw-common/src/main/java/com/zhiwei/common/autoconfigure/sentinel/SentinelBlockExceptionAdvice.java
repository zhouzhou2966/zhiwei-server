package com.zhiwei.common.autoconfigure.sentinel;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import com.zhiwei.common.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sentinel 拦截的统一响应处理
 *
 * <p><b>为什么不给每个 {@code @SentinelResource} 配 blockHandler</b>：
 * Sentinel 的 {@code blockHandler} 必须与业务方法<b>参数列表一一对应</b>
 * （业务参数 + 一个 BlockException），意味着每加一个受保护的方法就要多写一个几乎相同的方法，
 * 而且返回类型还要跟业务方法对齐 —— 模板代码会迅速失控。
 * <p>换个角度：Sentinel 在 {@code blockHandler} 缺失时会把 {@link BlockException} 直接抛出来，
 * 这本来就是一个标准异常，交给全局异常处理器统一处理更符合 Spring MVC 的既有约定，
 * 也让"被限流"和"业务报错"共用同一套 {@code R} 响应结构。
 **/
@Slf4j
@RestControllerAdvice
public class SentinelBlockExceptionAdvice {

    @ExceptionHandler(BlockException.class)
    public R<Void> handleBlockException(BlockException e) {
        String msg = resolveMessage(e);
        log.warn("Sentinel 拦截请求，规则类型: {}, 原因: {}", e.getClass().getSimpleName(), e.getMessage());
        return R.error(HttpStatus.TOO_MANY_REQUESTS.value(), msg);
    }

    /**
     * 按拦截原因给出面向用户的提示。
     * <p>对用户来说这些都是"稍后再试"，但区分开才能让前端/客服判断是该等待还是该上报：
     * 限流是流量问题、熔断是依赖故障、热点是单个参数被打爆。
     */
    private String resolveMessage(BlockException e) {
        if (e instanceof FlowException) {
            return "请求过于频繁，请稍后再试";
        }
        if (e instanceof DegradeException) {
            return "服务暂时不可用，请稍后再试";
        }
        if (e instanceof ParamFlowException) {
            return "当前操作过于集中，请稍后再试";
        }
        if (e instanceof SystemBlockException) {
            return "系统繁忙，请稍后再试";
        }
        if (e instanceof AuthorityException) {
            return "无权访问该资源";
        }
        return "请求被拒绝，请稍后再试";
    }
}
