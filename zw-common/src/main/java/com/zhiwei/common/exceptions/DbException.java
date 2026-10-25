package com.zhiwei.common.exceptions;

/**
 * 数据库异常。
 * <p>
 * <b>为什么默认 code 显式给 500</b>：父类 {@link CommonException#CommonException(String)}
 * 把 {@code code} 写死成 0，{@code DbException} 原来直接调 {@code super(message)}，
 * 于是数据库故障会返回 {@code HTTP 500 + {"code":0,...}}。{@code code:0} 会被前端
 * 当成「成功但没有数据」，把一个服务端故障伪装成正常空结果。
 * 数据库异常就是 500，这里把 code 与 status 对齐。
 * </p>
 **/
public class DbException extends CommonException{
    public DbException(String message) {
        super(500, message);
    }

    public DbException(int code, String message) {
        super(code, message);
    }

    public DbException(int code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
