package com.zhiwei.common.autoconfigure.idempotent.annotations;

import com.zhiwei.common.autoconfigure.idempotent.enums.IdempotentType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 幂等控制注解
 * <p>标注在 Service 方法上，由 {@code IdempotentAspect} 环绕拦截。
 * <p>核心语义：<b>同一个幂等键在幂等窗口内，业务只会真正执行一次</b>。
 * 窗口期内重复到达的请求有两种结局：
 * <ul>
 *     <li>首次请求尚未结束 → 判定为"处理中"，直接拒绝</li>
 *     <li>首次请求已成功 → 判定为"已完成"，拒绝，或直接返回首次的返回值（keepResult=true）</li>
 * </ul>
 * 首次请求<b>业务失败</b>时会主动释放幂等位，因此失败后的重试是允许的 ——
 * 这是幂等与"分布式锁"最容易混淆的地方：幂等锁的是"业务结果"，不是"执行过程"。
 **/
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {

    /**
     * 幂等键表达式，支持 SPEL，形如 {@code coupon:receive:#{userId}:#{couponId}}
     * <p>TOKEN 模式下该值被忽略（令牌本身就是幂等键）。
     * <p>留空时会自动退化为「方法签名 + 参数摘要」的 MD5，做到零配置可用。
     */
    String key() default "";

    /**
     * 幂等模式，默认按业务参数推导幂等键
     */
    IdempotentType type() default IdempotentType.PARAM;

    /**
     * 幂等窗口时长：超过该时长后幂等位自动失效，同键请求可以再次执行。
     * <p>窗口多长取决于业务：领券这类"永久唯一"的语义应当由 DB 唯一索引兜底，
     * 注解只负责挡住瞬时并发与短时间重试。
     */
    long timeout() default 10;

    /**
     * 时间单位，默认秒
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 重复提交时返回给用户的提示语
     */
    String message() default "请勿重复提交";

    /**
     * 是否缓存首次请求的返回值，重复请求直接返回同一结果（而不是报错）。
     * <p>适用于"查询/创建"这类重复请求期望拿到相同结果的接口，例如创建订单返回订单号。
     * <p>仅在 PARAM 模式下生效；TOKEN 模式语义是"一次性消费"，不返回结果。
     */
    boolean keepResult() default false;

    /**
     * 返回值缓存时长。仅在 {@link #keepResult()} 为 true 时生效。
     * <p>注意：缓存的 key 与幂等位是同一个，因此该时长同时决定了"结果可被重复获取"的窗口。
     */
    long resultTimeout() default 60;

    /**
     * 返回值缓存的时间单位，默认秒
     */
    TimeUnit resultTimeUnit() default TimeUnit.SECONDS;
}
