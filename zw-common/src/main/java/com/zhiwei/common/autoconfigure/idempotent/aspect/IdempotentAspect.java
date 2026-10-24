package com.zhiwei.common.autoconfigure.idempotent.aspect;

import com.zhiwei.common.autoconfigure.idempotent.IdempotentConstants;
import com.zhiwei.common.autoconfigure.idempotent.IdempotentProperties;
import com.zhiwei.common.autoconfigure.idempotent.annotations.Idempotent;
import com.zhiwei.common.autoconfigure.idempotent.enums.IdempotentType;
import com.zhiwei.common.autoconfigure.idempotent.storage.IdempotentStorage;
import com.zhiwei.common.autoconfigure.idempotent.storage.RedisIdempotentStorage;
import com.zhiwei.common.autoconfigure.idempotent.token.IdempotentTokenService;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.utils.AspectUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.common.utils.WebUtils;
import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.util.ObjectUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 幂等切面
 * <p>PARAM 模式下的状态机（幂等位只有三种状态）：
 * <pre>
 *                     ┌──────────────────────────────────────┐
 *                     │  tryAcquire 成功 → 写入 owner        │
 *   请求到达 ─────────▶│            （状态：处理中）           │
 *                     └──────────────┬───────────────────────┘
 *                                    │ 执行业务
 *              ┌─────────────────────┴─────────────────────┐
 *              ▼                                           ▼
 *        业务抛异常                                   业务正常返回
 *              │                                           │
 *     release（校验 owner 后删除）              markSuccess（校验 owner 后改写）
 *              │                                           │
 *              ▼                                           ▼
 *      幂等位消失，可重试                        状态：已完成（可带返回值）
 *                                                          │
 *                                    重复请求到达 ──────────┘
 *                                    → 拒绝，或直接返回缓存的结果
 * </pre>
 * <p>为什么"业务失败要释放幂等位"是必须的：如果只做 SETNX 不释放，
 * 一次因库存不足/参数校验失败导致的业务异常，会把幂等位一直占着，
 * 用户在窗口期内无论怎么重试都被判定成"重复提交"，这在生产上是事故。
 * 幂等要挡的是"重复成功"，不是"重复尝试"。
 *
 * <p><b>为什么切面顺序要尽量靠外，但又不能用 {@link Ordered#HIGHEST_PRECEDENCE}</b>：
 * Spring 的 {@code @Transactional}、Seata 的 {@code @GlobalTransactional} 默认都挂在切面链内层，
 * 本切面排在它们<b>外层</b>后，于是天然得到两个正确语义：
 * <ol>
 *     <li>"标记成功"发生在事务提交之后 —— 事务回滚时不会留下一个"成功"的假标记</li>
 *     <li>"释放幂等位"也发生在事务回滚之后 —— 回滚了就能重试</li>
 * </ol>
 * 反过来，如果幂等切面在事务内层，一旦外层事务回滚，幂等位已经被标记成功，
 * 后续重试会被拒绝，而业务其实根本没生效 —— 这是最典型的幂等踩坑方式。
 *
 * <p><b>那为什么不能直接用 {@code HIGHEST_PRECEDENCE}</b>：
 * {@code @Around("@annotation(idempotent)")} 需要把注解对象注入通知方法的第二个参数，
 * 这一步依赖 Spring 在调用链里做动态匹配时，把 {@code JoinPointMatch} 作为 user attribute
 * 挂到当前的 MethodInvocation 上；而挂载动作要求
 * {@code ExposeInvocationInterceptor.currentInvocation()} 能取到调用上下文。
 * {@code ExposeInvocationInterceptor} 自身的 order 是 {@code HIGHEST_PRECEDENCE + 1}，
 * 一旦本切面取 {@code HIGHEST_PRECEDENCE}，就会排到它<b>前面</b>：此时 ThreadLocal 还是空的，
 * 匹配阶段抛出的 {@code IllegalStateException} 被 Spring 静默吞掉（只在 debug 日志里），
 * {@code JoinPointMatch} 不再挂载，等通知真正执行时就会抛：
 * <pre>
 * IllegalStateException: Required to bind 2 arguments, but only bound 1
 *     (JoinPointMatch was NOT bound in invocation)
 * </pre>
 * 所以这里取 {@code HIGHEST_PRECEDENCE + 2}：既在 {@code ExposeInvocationInterceptor} 之内，
 * 又仍在事务切面（{@code LOWEST_PRECEDENCE}）之外，两个诉求同时满足。
 **/
@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class IdempotentAspect {

    /**
     * SPEL 表达式匹配规则，与 {@code LockAspect} 保持一致
     */
    private static final Pattern SPEL_PATTERN = Pattern.compile("\\#\\{([^\\}]*)\\}");
    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final IdempotentStorage storage;
    private final IdempotentProperties properties;
    private final IdempotentTokenService tokenService;
    private final ObjectMapper objectMapper;

    public IdempotentAspect(IdempotentStorage storage,
                            IdempotentProperties properties,
                            IdempotentTokenService tokenService,
                            ObjectMapper objectMapper) {
        this.storage = storage;
        this.properties = properties;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object handleIdempotent(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }
        if (idempotent.type() == IdempotentType.TOKEN) {
            return handleByToken(pjp, idempotent);
        }
        return handleByKey(pjp, idempotent);
    }

    /* ==================== 令牌模式 ==================== */

    private Object handleByToken(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String token = WebUtils.getHeader(properties.getTokenHeader());
        if (StringUtils.isBlank(token)) {
            throw new BizIllegalException("请求缺少幂等令牌");
        }
        // 原子消费令牌：删成功才放行，多实例并发下只会有一个成功
        if (!tokenService.consume(token)) {
            throw new BizIllegalException(idempotent.message());
        }
        try {
            return pjp.proceed();
        } catch (Throwable e) {
            // 业务失败，归还令牌供客户端重试
            tokenService.giveBack(token);
            throw e;
        }
    }

    /* ==================== 幂等键模式 ==================== */

    private Object handleByKey(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        // 1.推导幂等键，并为本次请求生成唯一的持有者标识
        String key = properties.getKeyPrefix() + resolveKey(pjp, idempotent);
        String owner = UUID.randomUUID().toString();
        Duration ttl = Duration.ofMillis(idempotent.timeUnit().toMillis(idempotent.timeout()));

        // 2.抢占幂等位
        if (!storage.tryAcquire(key, owner, ttl)) {
            return handleRepeatedRequest(pjp, idempotent, key);
        }

        // 3.抢占成功，执行业务
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable e) {
            // 业务失败 → 释放幂等位，允许客户端重试
            storage.release(key, owner);
            throw e;
        }

        // 4.业务成功 → 标记完成（可选缓存返回值）
        Duration resultTtl = Duration.ofMillis(
                idempotent.resultTimeUnit().toMillis(idempotent.resultTimeout()));
        boolean marked = storage.markSuccess(key, owner, resultTtl, buildMarkedValue(pjp, idempotent, result));
        if (!marked) {
            // 走到这里说明幂等位在业务执行期间已过期并被他人抢占，
            // 属于「业务执行时间 > 幂等窗口」的配置问题，需要调大 timeout
            log.warn("幂等位标记失败，可能幂等窗口过短，key: {}", key);
        }
        return result;
    }

    /**
     * 处理重复到达的请求
     */
    private Object handleRepeatedRequest(ProceedingJoinPoint pjp, Idempotent idempotent, String key) {
        String value = storage.get(key);
        if (RedisIdempotentStorage.isSuccess(value)) {
            // 首次请求已成功
            if (idempotent.keepResult()) {
                String json = RedisIdempotentStorage.extractResult(value);
                if (StringUtils.isNotBlank(json)) {
                    return parseCachedResult(pjp, json);
                }
            }
            throw new BizIllegalException(idempotent.message());
        }
        // 首次请求仍在处理中，或幂等位刚好过期
        throw new BizIllegalException(properties.getProcessingMessage());
    }

    /**
     * 构造写入幂等位的完成标记
     */
    private String buildMarkedValue(ProceedingJoinPoint pjp, Idempotent idempotent, Object result) {
        if (!idempotent.keepResult() || isVoidMethod(pjp)) {
            return IdempotentConstants.SUCCESS_MARK;
        }
        String json = toJson(result);
        return IdempotentConstants.SUCCESS_MARK + (json == null ? "" : json);
    }

    /**
     * 把缓存的结果反序列化成方法的返回类型
     */
    private Object parseCachedResult(ProceedingJoinPoint pjp, String json) {
        Class<?> returnType = ((MethodSignature) pjp.getSignature()).getReturnType();
        if (Void.TYPE.equals(returnType)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, returnType);
        } catch (Exception e) {
            log.warn("幂等结果反序列化失败，将按重复提交处理，json: {}", json, e);
            return null;
        }
    }

    /**
     * 序列化。用注入的 ObjectMapper 而不是工具类，
     * 是为了让缓存里的时间格式、字段命名和接口返回保持一致，
     * 否则 LocalDateTime 这类字段在不同序列化器下格式不同，反序列化会直接失败。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("幂等结果序列化失败，本次不缓存返回值", e);
            return null;
        }
    }

    private boolean isVoidMethod(ProceedingJoinPoint pjp) {
        return Void.TYPE.equals(((MethodSignature) pjp.getSignature()).getReturnType());
    }

    /**
     * 推导幂等键
     * <p>三种情况：
     * <ol>
     *     <li>注解未指定 key → 用「方法全名 + 参数 JSON」的 MD5 兜底，零配置可用</li>
     *     <li>指定了但不含 SPEL → 直接使用</li>
     *     <li>含 {@code #{...}} → 解析后拼接</li>
     * </ol>
     * <p>这里没有复用 {@code SPELUtils}，原因是它的实现依赖 {@code LocalVariableTableParameterNameDiscoverer}，
     * 拿不到参数名时会在内部抛 NPE；改用 {@code DefaultParameterNameDiscoverer} 可以兼容
     * 编译期 {@code -parameters}、{@code @Param} 注解等多种命名来源，更稳。
     * 另外这里用 StringBuilder 拼接而不是 {@code String.replace}，
     * 避免参数值本身含有 {@code #{}} 时被二次替换。
     */
    private String resolveKey(ProceedingJoinPoint pjp, Idempotent idempotent) {
        String key = idempotent.key();
        Method method = AspectUtils.getMethod(pjp);
        if (StringUtils.isBlank(key)) {
            return autoKey(method, pjp.getArgs());
        }
        if (!key.contains("#")) {
            return key;
        }
        EvaluationContext context = new MethodBasedEvaluationContext(
                TypedValue.NULL, method, pjp.getArgs(), NAME_DISCOVERER);
        // 注入内置变量：用户身份来自 ThreadLocal 而非方法参数，但幂等键几乎总要带上它。
        // MethodBasedEvaluationContext 查找变量时优先命中这里注入的，其次才是方法参数，
        // 因此内置变量名与参数名冲突时以内置变量为准（框架约定）。
        Long userId = UserContext.getUserId();
        if (userId != null) {
            context.setVariable(IdempotentConstants.VAR_USER_ID, userId);
        }
        ExpressionParser parser = new SpelExpressionParser();
        Matcher matcher = SPEL_PATTERN.matcher(key);
        StringBuilder sb = new StringBuilder(key.length() + 16);
        int cursor = 0;
        while (matcher.find()) {
            sb.append(key, cursor, matcher.start());
            Expression expression = parser.parseExpression("#" + matcher.group(1));
            Object value = expression.getValue(context);
            sb.append(ObjectUtils.nullSafeToString(value));
            cursor = matcher.end();
        }
        sb.append(key, cursor, key.length());
        return sb.toString();
    }

    /**
     * 零配置兜底：用「类名#方法名 + 全部参数」的摘要作为幂等键
     * <p>参数序列化失败时退化为「类名#方法名」，此时幂等粒度会变粗（同方法的所有请求互斥），
     * 属于保守降级 —— 宁可多挡，不可漏挡。
     */
    private String autoKey(Method method, Object[] args) {
        String prefix = method.getDeclaringClass().getName() + "#" + method.getName();
        String argsJson = toJson(args);
        if (argsJson == null) {
            log.warn("幂等键自动推导时参数序列化失败，退化为方法级幂等：{}", prefix);
            argsJson = StringUtils.EMPTY;
        }
        return DigestUtil.md5Hex(prefix + ":" + argsJson);
    }
}
