package com.zhiwei.common.exceptions;

import lombok.Getter;

/**
 * 业务不合法异常（参数非法、状态不允许、前置条件不满足等）。
 * <p>
 * <b>为什么显式声明 {@code status} 和默认 code 400</b>：父类
 * {@link CommonException#CommonException(String)} 把 {@code code} 写死成 0，
 * 而 {@link CommonException#getStatus()} 默认返回 500。这个类原来直接调
 * {@code super(message)}，于是业务校验失败会返回
 * {@code HTTP 500 + {"code":0,...}} —— 前端拿到 {@code code:0} 会当成
 * 「成功但没有数据」，错误被静默吞掉；HTTP 500 也让调用方误以为是服务端故障。
 * <p>
 * 这里按 {@link BadRequestException} 的写法补上 {@code status = 400}：
 * 「业务规则不满足」本质是调用方请求的问题，不是服务端异常。
 * </p>
 **/
@Getter
public class BizIllegalException extends CommonException{

    private final int status = 400;

    public BizIllegalException(String message) {
        super(400, message);
    }

    public BizIllegalException(int code, String message) {
        super(code, message);
    }

    public BizIllegalException(int code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
