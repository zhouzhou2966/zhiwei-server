package com.zhiwei.promotion.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.exceptions.DbException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.JsonUtils;
import com.zhiwei.common.utils.NumberUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.promotion.cache.CouponCacheManager;
import com.zhiwei.promotion.constants.PromotionConstants;
import com.zhiwei.promotion.domian.UserCouponDTO;
import com.zhiwei.promotion.domian.po.Coupon;
import com.zhiwei.promotion.domian.po.ExchangeCode;
import com.zhiwei.promotion.domian.po.UserCoupon;
import com.zhiwei.promotion.domian.query.UserCouponQuery;
import com.zhiwei.promotion.domian.vo.CouponVO;
import com.zhiwei.promotion.domian.vo.ExchangePreviewVO;
import com.zhiwei.promotion.enums.CouponStatus;
import com.zhiwei.promotion.enums.ExchangeCodeStatus;
import com.zhiwei.promotion.enums.ObtainType;
import com.zhiwei.promotion.enums.UserCouponStatus;
import com.zhiwei.promotion.mapper.CouponMapper;
import com.zhiwei.promotion.mapper.UserCouponMapper;
import com.zhiwei.promotion.service.IExchangeCodeService;
import com.zhiwei.promotion.service.ICouponScopeService;
import com.zhiwei.promotion.service.IUserCouponService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.promotion.strategy.discount.DiscountStrategy;
import com.zhiwei.promotion.utils.CodeUtil;

import lombok.RequiredArgsConstructor;


import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.zhiwei.common.constants.MqConstants.Exchange.PROMOTION_EXCHANGE;
import static com.zhiwei.common.constants.MqConstants.Key.COUPON_RECEIVE_KEY;
import static com.zhiwei.promotion.constants.PromotionConstants.*;


/**
 * <p>
 * 用户领取优惠券的记录，是真正使用的优惠券信息 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-29
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {
    private final CouponMapper couponMapper;
    private final IExchangeCodeService exchangeCodeService;
    private final ICouponScopeService couponScopeService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitMqHelper rabbitMqHelper;
    private final CouponCacheManager couponCacheManager;
    private static final RedisScript<Long> RECEIVE_COUPON_SCRIPT;
    private static final RedisScript<String> EXCHANGE_COUPON_SCRIPT;
    private static final RedisScript<Long> ROLLBACK_RECEIVE_COUPON_SCRIPT;
    private static final RedisScript<Long> ROLLBACK_EXCHANGE_COUPON_SCRIPT;

    /**
     * 领券幂等键前缀。键的组成是「业务唯一键」userId:couponId，不是 MQ 的 messageId ——
     * messageId 属于传输层，重发或改延迟重投后每次都会变，用它挡不住重复。
     */
    private static final String DEDUP_KEY_PREFIX = "msg:dedup:coupon:";
    /**
     * 幂等键有效期。只需覆盖「消息可能被重投」的窗口，24 小时足够。
     */
    private static final Duration DEDUP_KEY_TTL = Duration.ofHours(24);

    /**
     * 缓存重建时，没抢到锁的线程的等待时长。
     * <p>重建临界区只有一次 selectById + 一次 HSET，正常在毫秒级完成；
     * 50ms 足够覆盖，同时不会让用户在领券页干等。</p>
     */
    private static final long REBUILD_WAIT_MS = 50L;

    /**
     * 兑换码反查券id 时在 ZSet 上取的序列号区间宽度。
     * <p>兑换码的序列号是独立申请的，同一个券的码在 ZSet 里不一定连续，
     * 所以取一个足够宽的窗口，命中第一条即可（与 exchange_coupon.lua 里的 5000 保持一致）。</p>
     */
    private static final long EXCHANGE_CODE_RANGE = 5000L;

    /**
     * 领券 Lua 的错误码 4：超出领取数量。
     * <p>拿到它时不能直接抛异常，要先确认是真到限还是计数丢了（见 repairUserCounterIfMissing）。</p>
     */
    private static final int RECEIVE_LIMIT_REACHED = 4;

    static {
        RECEIVE_COUPON_SCRIPT = RedisScript.of(new ClassPathResource("lua/receive_coupon.lua"), Long.class);
        EXCHANGE_COUPON_SCRIPT = RedisScript.of(new ClassPathResource("lua/exchange_coupon.lua"), String.class);
        ROLLBACK_RECEIVE_COUPON_SCRIPT = RedisScript.of(new ClassPathResource("lua/rollback_receive_coupon.lua"), Long.class);
        ROLLBACK_EXCHANGE_COUPON_SCRIPT = RedisScript.of(new ClassPathResource("lua/rollback_exchange_coupon.lua"), Long.class);
    }

    @Override
    // 资源名用于在 Sentinel 控制台/Nacos 里配置规则。
    // 领券是典型的"瞬时热点"接口：秒杀开场几秒内所有流量集中到少数几个 couponId 上，
    // 因此这里除了流控，还会配合 param-flow-rules 对参数 0（couponId）做热点限流，
    // 避免单个爆款券把整个服务线程池打满。
    // blockHandler：被限流时返回业务提示，而不是让 BlockException 冒泡成 500
    @SentinelResource(value = "receiveCoupon", blockHandler = "receiveCouponBlockHandler")
    public void receiveCoupon(Long couponId) {
        if (couponId == null || couponId <= 0) {
            throw new BadRequestException("无效的优惠券ID");
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizIllegalException("用户未登录");
        }
        // 1.执行LUA脚本，判断结果
        // 1.1.准备参数
        String key1 = COUPON_CACHE_KEY_PREFIX + couponId;
        String key2 = USER_COUPON_CACHE_KEY_PREFIX + couponId;
        // 1.2.执行脚本（命中「缓存不存在」时回源 DB 重建缓存再重试一次）
        int result = receiveWithCacheRepair(couponId, userId, key1, key2);
        if (result == RECEIVE_LIMIT_REACHED) {
            result = repairUserCounterIfMissing(couponId, userId, key1, key2);
        }
        if (result != 0) {
            throw new BizIllegalException(PromotionConstants.RECEIVE_COUPON_ERROR_MSG[result - 1]);
        }
       // 2. 发送MQ异步落库
        UserCouponDTO uc = new UserCouponDTO();
        uc.setUserId(userId);
        uc.setCouponId(couponId);
        // 每次领取请求一个独立幂等标识：user_limit 允许同一用户领多张同款券，
        // 幂等键如果只用「用户 + 券」，第二张在消费端会被当成重复消息丢掉。
        uc.setRequestId(UUID.randomUUID().toString());
        try {
            boolean send = rabbitMqHelper.send(PROMOTION_EXCHANGE, COUPON_RECEIVE_KEY, uc);
            if (!send) throw new RuntimeException("MQ发送失败");
        } catch (Exception e) {
            // 3. 使用原子Lua脚本回滚：用户计数-1 + 库存+1
            redisTemplate.execute(ROLLBACK_RECEIVE_COUPON_SCRIPT, List.of(key1, key2), userId.toString());
            log.error("领取优惠券MQ发送异常，couponId: {}", couponId, e);
            throw new BizIllegalException("领取优惠券失败，请稍后重试");
        }
    }

    /**
     * receiveCoupon 的限流降级方法。
     * <p>Sentinel 要求 blockHandler 与被保护方法同签名（末尾多一个 BlockException），且必须是 static。
     * 没有它的话被限流时 BlockException 会一路冒泡成 500，前端只能看到「系统错误」。</p>
     */
    public static void receiveCouponBlockHandler(Long couponId, BlockException ex) {
        log.warn("领券触发限流，couponId={}, rule={}", couponId, ex.getClass().getSimpleName());
        throw new BizIllegalException("当前领券人数过多，请稍后再试");
    }

    /**
     * 执行领券 Lua。缓存不存在（错误码 1）时回源 DB 重建缓存，然后重试一次。
     * <p>
     * 「优惠券不存在」这个文案过去由 Lua 的 {@code exists prs:coupon:{id}} 直接决定，
     * 但它只能证明 Redis 里没有库存缓存，证明不了 DB 里没有这张券。Redis 重启、
     * key 被驱逐、状态被绕过接口改动（手工 SQL 把 status 改成发放中）都会命中这一支，
     * 表现就是「列表里看得到、点领取说券不存在」，且不会自愈。
     * </p>
     * <p>
     * 这里把错误码 1 拆成两种真实含义：DB 里确实不可领（返回 1，文案正确），
     * 或者只是缓存缺失（重建后重跑，用户无感）。DB 侧的
     * {@code issue_num < total_num} 原子校验（{@code increaseIssueNum}）仍然是超发的最终防线，
     * 所以重建不会造成超发 —— 注意它挡的是「发得比库存多」，
     * 「同一个人多领一张」由 Lua 的用户计数 + user_limit 负责，不再靠 user_coupon 的唯一索引
     * （那个索引已随「一人可领多张」的需求移除）。
     * </p>
     */
    private int receiveWithCacheRepair(Long couponId, Long userId, String key1, String key2) {
        int result = runReceiveScript(key1, key2, userId);
        if (result != 1) return result;

        if (!couponCacheManager.tryLockRebuild(couponId)) {
            // 已有线程在重建：等它完成再重试，避免并发重建互相覆盖库存。
            // 临界区只有一次 selectById + 一次 HSET，50ms 足够；仍失败就按原错误码返回。
            sleepQuietly(REBUILD_WAIT_MS);
            return runReceiveScript(key1, key2, userId);
        }
        try {
            Coupon coupon = couponMapper.selectById(couponId);
            if (coupon == null || coupon.getStatus() != CouponStatus.ISSUING) {
                // 真的不可领：DB 都没有，或已结束/暂停/未开始 —— 保持「优惠券不存在」的原语义
                return 1;
            }
            couponCacheManager.preheat(coupon);
            log.warn("[COUPON-REBUILD] 库存缓存缺失，已按 DB 重建：couponId={}, totalNum={}, issueNum={}",
                    couponId, coupon.getTotalNum(), coupon.getIssueNum());
        } catch (Exception e) {
            // 重建失败（DB 抖动等）不能改变对外语义，但必须留痕，否则又变成静默故障
            log.error("[COUPON-REBUILD] 缓存重建失败：couponId={}", couponId, e);
            return 1;
        } finally {
            couponCacheManager.releaseRebuildLock(couponId);
        }
        return runReceiveScript(key1, key2, userId);
    }

    private int runReceiveScript(String key1, String key2, Long userId) {
        Long r = redisTemplate.execute(RECEIVE_COUPON_SCRIPT, List.of(key1, key2), userId.toString());
        return NumberUtils.null2Zero(r).intValue();
    }

    /**
     * 错误码 4（超出领取数量）时的一次自愈尝试。
     *
     * <p>用户领取计数在 {@code prs:user:coupon:{couponId}} 这个 hash 里，没有 TTL，
     * 但 Redis 重启 / FLUSHDB 会把它整块丢掉。丢掉的后果不是报错而是「限额失效」：
     * 计数归零后同一个人可以再领 userLimit 张。券缓存丢了还能靠错误码 1 回源重建，
     * 用户计数一直没有对应的入口。</p>
     *
     * <p><b>只在「这个用户的字段完全不存在」时补写</b>，字段存在但数值和 DB 不一致时不动：
     * 那种差值通常来自「Lua 已 +1、MQ 还没落库」的在途窗口，按 DB 覆盖会让用户多领一张。
     * 也就是说这里只修「整块丢了」，不修「正在飞的那一张」。</p>
     */
    private int repairUserCounterIfMissing(Long couponId, Long userId, String key1, String key2) {
        Object cached = redisTemplate.opsForHash().get(key2, userId.toString());
        if (cached != null) {
            // 计数还在，说明是真到限
            return RECEIVE_LIMIT_REACHED;
        }
        long owned = lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, couponId)
                .count();
        if (owned <= 0) {
            // DB 里也没有领取记录，那不是「丢了」而是配置或数据异常（例如 userLimit 配成 0），
            // 保持原错误码，不要凭空造一个计数出来
            return RECEIVE_LIMIT_REACHED;
        }
        redisTemplate.opsForHash().put(key2, userId.toString(), String.valueOf(owned));
        log.warn("[COUPON-COUNTER-REPAIR] 用户领取计数缺失，已按 DB 重建：couponId={}, userId={}, count={}",
                couponId, userId, owned);
        return runReceiveScript(key1, key2, userId);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 恢复中断标记，让上层能感知到取消信号
            Thread.currentThread().interrupt();
        }
    }


    @Transactional
    @Override
    public void checkAndCreateUserCoupon(UserCouponDTO uc, String messageId) {
        // 0. 前置校验：幂等键由 userId + couponId 拼成，任一为空都会退化成「所有消息共用一个键」，
        //    那种情况下第一条之后的领券消息会被全部误判为重复，必须直接拦住。
        if (uc.getUserId() == null || uc.getCouponId() == null) {
            throw new BadRequestException("领券消息缺少 userId 或 couponId");
        }
        // 1. SETNX 幂等闸门
        //    键用「一次领取请求」而不是「用户 + 券」：
        //    ① user_limit 本来就允许同一用户领多张同款券，用 (userId, couponId) 做键，
        //       第二张合法领取会被当成重复投递直接 return —— 而 Lua 那边库存和计数已经扣了，
        //       结果就是「静默丢券」：用户看到兑换成功，列表里却没有券，日志也查不出东西。
        //    ② 幂等要挡的是「同一条消息被消费两次」，所以键必须按请求区分。
        //    requestId 为空时退回老的 (userId, couponId)，兼容滚动升级期间还在队列里的旧消息。
        String requestKey = uc.getRequestId() == null ? "legacy" : uc.getRequestId();
        String dedupKey = DEDUP_KEY_PREFIX + uc.getUserId() + ":" + uc.getCouponId() + ":" + requestKey;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_KEY_TTL);
        if (Boolean.FALSE.equals(first)) {
            // 键存在只能说明「处理过」，不能说明「处理成功」：
            // 消费中途进程被 kill（发布重启、OOM）时 key 会原样残留 24 小时，消息重投后如果直接
            // return，就会出现「Redis 库存已扣、DB 没记录、也没人回滚」的静默丢券，且日志里查不到。
            // 所以这里必须回查一次 DB —— 有记录才是真的处理过。
            // 回查也必须按 requestId 精确查：按 (userId, couponId) 查会把「用户早先领过一张」
            // 误判成「这次请求已处理」，那正是下面第 ① 条要避免的错。
            Long exists = uc.getRequestId() == null
                    ? lambdaQuery().eq(UserCoupon::getUserId, uc.getUserId())
                        .eq(UserCoupon::getCouponId, uc.getCouponId()).count()
                    : lambdaQuery().eq(UserCoupon::getRequestId, uc.getRequestId()).count();
            if (exists != null && exists > 0) {
                log.info("重复消息，本请求已有落库记录，跳过：userId={}, couponId={}, requestId={}, messageId={}",
                        uc.getUserId(), uc.getCouponId(), uc.getRequestId(), messageId);
                return;
            }
            // DB 没记录 = 上次处理到一半中断了（事务已回滚，issue_num 也一起回滚），继续往下走是安全的
            log.warn("幂等键命中但 DB 无记录，判定为上次处理中断，继续处理：userId={}, couponId={}, requestId={}, messageId={}",
                    uc.getUserId(), uc.getCouponId(), uc.getRequestId(), messageId);
        }
        try {
            // 2. 校验优惠券是否存在
            Coupon coupon = couponMapper.selectById(uc.getCouponId());
            if (coupon == null) throw new BadRequestException("优惠券不存在");
            // 3. 原子递增发放数量，校验库存
            int result = couponMapper.increaseIssueNum(coupon.getId());
            if (result <= 0) throw new BadRequestException("优惠券已发放完毕");
            // 4. 保存用户领取记录
            try {
                saveUserCoupon(uc.getUserId(), coupon, uc.getRequestId());
            } catch (DuplicateKeyException e) {
                // 唯一索引 uk_user_coupon_request(request_id) 兜底：同一个 requestId 只能落一条。
                // 走到这里说明这条请求已经成功落过库了，抛业务异常让事务回滚（把本次多扣的
                // issue_num 退回去），外层再回滚 Redis。
                // ⚠️ 注意这里不再是「您已领取过该优惠券」：user_limit 允许重复领取，
                //    「用户已持有该券」不再是失败条件。
                throw new BizIllegalException("该领取请求已处理，请勿重复提交");
            }
            // 5. 若是兑换码方式领取，更新兑换码状态为已使用
            if (coupon.getObtainWay() == ObtainType.ISSUE && uc.getCodeId() != null) {
                exchangeCodeService.lambdaUpdate()
                        .eq(ExchangeCode::getId, uc.getCodeId())
                        .set(ExchangeCode::getUserId, uc.getUserId())
                        .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                        .update();
            }
        } catch (Exception e) {
            // 落库失败要允许重试，所以得把去重 key 删掉 —— 但只能在「事务确实回滚」之后删。
            // 如果事务已经提交、只是提交后抛了异常（连接断开等），提前删 key 会让重试再插一条，
            // 用户就多领了一张。所以挂到事务同步回调里，按最终状态决定。
            removeDedupKeyAfterRollback(dedupKey);
            throw e;
        }
    }

    /**
     * 事务回滚后再删去重 key，保证「已提交但抛异常」的场景不会被重试重复插入
     */
    private void removeDedupKeyAfterRollback(String dedupKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            redisTemplate.delete(dedupKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    redisTemplate.delete(dedupKey);
                }
            }
        });
    }

    private void saveUserCoupon(Long userId, Coupon coupon, String requestId) {
        // 1. 构建用户优惠券记录
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setCouponId(coupon.getId());
        userCoupon.setUserId(userId);
        // 幂等标识跟着落库，唯一索引 uk_user_coupon_request 兜底「同一请求只落一条」
        userCoupon.setRequestId(requestId);
        // 2. 计算优惠券有效期
        LocalDateTime termBeginTime = coupon.getTermBeginTime();
        LocalDateTime termEndTime = coupon.getTermEndTime();
        if (termBeginTime == null) {
            // 未指定有效期，从当前时间开始按领取天数计算
            termBeginTime = LocalDateTime.now();
            int termDays = NumberUtils.null2Zero(coupon.getTermDays());
            // termDays 配成 0 或 null 说明券模板数据有问题，兜底给 1 天，避免 plusDays(null) 直接 NPE
            termEndTime = termBeginTime.plusDays(termDays <= 0 ? 1 : termDays);
        }
        userCoupon.setTermBeginTime(termBeginTime);
        userCoupon.setTermEndTime(termEndTime);
        save(userCoupon);
    }


    @Override
    // 兑换码接口同样可能被打，加上流控降级，避免 BlockException 冒泡成 500
    @SentinelResource(value = "exchangeCoupon", blockHandler = "exchangeCouponBlockHandler")
    public void exchangeCoupon(String code) {
        // 解析兑换码，提取自增序列号
        long serialNum = CodeUtil.parseCode(code);
        Long userId = UserContext.getUserId();
        // 1. 执行LUA脚本：防重检查 + ZSet反查 + 校验有效期/限领 + 原子标记
        //    返回结果：1~5为异常错误码，>10为couponId（优惠券ID）
        String result = redisTemplate.execute(EXCHANGE_COUPON_SCRIPT, List.of(COUPON_CODE_MAP_KEY, COUPON_CODE_RANGE_KEY),
                String.valueOf(serialNum), String.valueOf(serialNum + EXCHANGE_CODE_RANGE), userId.toString());
        // Lua 可能返回两种形态：纯错误码 "5"，或者带上下文的 "5|cid|已领张数|限额"
        String[] parts = result == null ? new String[0] : result.split("\\|");
        long r = NumberUtils.parseLong(parts.length > 0 ? parts[0] : result);
        if (r < 10) {
            // 错误码 5（超出领取数量）单独拼一句人话：
            // 兑换码场景里用户手里只有一串码，不知道换的是什么券、也不知道自己的额度用在哪了。
            // 只回「超出领取数量」，他会以为系统坏了 —— 这也是当初误判成「静默丢券」的原因之一。
            if (r == 5 && parts.length >= 4) {
                throw new BizIllegalException(buildOverLimitMessage(parts));
            }
            // 其余错误码（1~6），取对应错误信息抛异常
            throw new BizIllegalException(PromotionConstants.EXCHANGE_COUPON_ERROR_MSG[(int) (r - 1)]);
        }
        // 2. LUA执行成功，构建DTO并发送MQ异步落库
        Long couponId = r;
        UserCouponDTO uc = new UserCouponDTO();
        uc.setUserId(userId);
        uc.setCouponId(couponId);
        uc.setCodeId(serialNum);
        // 兑换码本身就是一次唯一的领取请求，序列号天然可做幂等键；
        // 重投时值不变，能挡住重复消费，又不会影响用户之后再领同款券。
        uc.setRequestId("exchange:" + serialNum);
        try {
            boolean send = rabbitMqHelper.send(PROMOTION_EXCHANGE, COUPON_RECEIVE_KEY, uc);
            if (!send) throw new RuntimeException("MQ发送失败");
        } catch (Exception e) {
            // 3. 使用原子Lua脚本回滚：Bitmap + 用户计数 + 库存
            redisTemplate.execute(ROLLBACK_EXCHANGE_COUPON_SCRIPT,
                    List.of(COUPON_CODE_MAP_KEY, USER_COUPON_CACHE_KEY_PREFIX + couponId, COUPON_CACHE_KEY_PREFIX + couponId),
                    String.valueOf(serialNum), userId.toString());
            log.error("兑换码兑换异常，serialNum: {}, couponId: {}", serialNum, couponId, e);
            throw new BizIllegalException("兑换码兑换失败，请稍后重试");
        }
    }

    /**
     * 把 Lua 返回的 {@code 5|cid|curCount|userLimit} 拼成一句用户能看懂的话。
     * <p>「超出领取数量」这句话在兑换码场景里是不成立的：用户手里只有一串码，
     * 既不知道换的是哪张券，也不知道自己的额度用在哪了。必须把券名和真实数量说出来。</p>
     */
    private String buildOverLimitMessage(String[] parts) {
        Long limitCouponId = NumberUtils.parseLong(parts[1]);
        int curCount = (int) NumberUtils.parseLong(parts[2]);
        int userLimit = (int) NumberUtils.parseLong(parts[3]);
        Coupon coupon = couponMapper.selectById(limitCouponId);
        String name = coupon == null ? "该优惠券" : "「" + coupon.getName() + "」";
        if (curCount >= userLimit) {
            return String.format("这个兑换码换的是%s，每人限领 %d 张，您已经有 %d 张了，不能再兑",
                    name, userLimit, curCount);
        }
        return String.format("这个兑换码换的是%s，暂时无法兑换", name);
    }

    /**
     * 兑换码预检：告诉用户这张码会换到什么券、现在能不能兑。
     *
     * <p>兑换码是个盲盒 —— 用户手里只有一串码。不做预检的话，只能点了「兑换」再被一句
     * 「超出领取数量」挡住，既不知道在跟什么限额较劲，也会觉得「有效码凭什么不给我」。
     * 预检把券面和限制提前摆出来，翻卡的惊喜回到「翻开看到面额」这一件事上。</p>
     *
     * <p>只读：不写 Redis、不占名额、不动库存，所以随便查。</p>
     */
    @Override
    public ExchangePreviewVO previewExchange(String code) {
        // 1. 解析码。格式非法直接抛 400，前端提示「无效兑换码」
        long serialNum = CodeUtil.parseCode(code);
        ExchangePreviewVO vo = new ExchangePreviewVO();
        vo.setValid(true);
        vo.setUsed(false);
        vo.setCanExchange(false);

        // 2. 是否已被兑换。使用状态在 Redis 位图里，不在 exchange_code.status（那一列是启用/停用）
        Boolean used = redisTemplate.opsForValue().getBit(COUPON_CODE_MAP_KEY, serialNum);
        if (Boolean.TRUE.equals(used)) {
            vo.setUsed(true);
            vo.setReason("该兑换码已被使用");
            return vo;
        }

        // 3. 反查券id，规则与 exchange_coupon.lua 完全一致
        Set<String> cids = redisTemplate.opsForZSet()
                .rangeByScore(COUPON_CODE_RANGE_KEY, serialNum, serialNum + EXCHANGE_CODE_RANGE);
        if (CollUtils.isEmpty(cids)) {
            vo.setReason("兑换码无效或已被回收");
            return vo;
        }
        Long couponId = NumberUtils.parseLong(cids.iterator().next());

        // 4. 券模板读 DB 而不是 Redis 缓存：预检要展示券名/面额/门槛，缓存里只有库存和限额；
        //    预检是低频操作，一次 selectById 的代价可以忽略
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            vo.setReason("兑换码对应的优惠券不存在");
            return vo;
        }
        vo.setCouponId(couponId);
        vo.setName(coupon.getName());
        vo.setDiscountType(coupon.getDiscountType());
        vo.setDiscountValue(coupon.getDiscountValue());
        vo.setThresholdAmount(coupon.getThresholdAmount());
        vo.setMaxDiscountAmount(coupon.getMaxDiscountAmount());
        vo.setTermDays(coupon.getTermDays());

        int userLimit = NumberUtils.null2Zero(coupon.getUserLimit());
        if (userLimit <= 0) userLimit = 1;
        vo.setUserLimit(userLimit);

        // 5. 「已持有几张」按 DB 行数算，不按 Redis 计数：
        //    「我的优惠券」里看到的就是 DB 行数（同款多张聚合成 ×N），
        //    预检必须和列表对得上，否则用户会认为两边数据打架。
        long owned = lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUserId())
                .eq(UserCoupon::getCouponId, couponId)
                .count();
        vo.setUserCount((int) owned);

        // 6. 按 Lua 的校验顺序逐条给原因，先撞到哪条就说哪条
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getStatus() != CouponStatus.ISSUING) {
            vo.setReason("该优惠券已停止发放");
            return vo;
        }
        if (coupon.getIssueBeginTime() != null && now.isBefore(coupon.getIssueBeginTime())) {
            vo.setReason("兑换活动尚未开始");
            return vo;
        }
        if (coupon.getIssueEndTime() != null && now.isAfter(coupon.getIssueEndTime())) {
            vo.setReason("该兑换码已过期");
            return vo;
        }
        if (NumberUtils.null2Zero(coupon.getIssueNum()) >= NumberUtils.null2Zero(coupon.getTotalNum())) {
            vo.setReason("该优惠券已兑完");
            return vo;
        }
        if (owned >= userLimit) {
            vo.setReason(String.format("每人限领 %d 张，您已持有 %d 张", userLimit, owned));
            return vo;
        }

        vo.setCanExchange(true);
        vo.setReason(null);
        return vo;
    }

    /**
     * exchangeCoupon 的限流降级方法（签名必须与被保护方法一致 + BlockException）
     */
    public static void exchangeCouponBlockHandler(String code, BlockException ex) {
        log.warn("兑换码兑换触发限流，rule={}", ex.getClass().getSimpleName());
        throw new BizIllegalException("当前兑换人数过多，请稍后再试");
    }

    /**
     * 分页查询我的优惠券     * <p>个人中心「我的优惠券」用三个 Tab（未使用 / 已使用 / 已过期）切换，
     * 每次只查一个状态，因此按 userId + status 做等值过滤，命中
     * user_coupon(user_id, status) 索引。</p>
     * <p>查询结果按有效期截止时间升序，快过期的排在前面，方便用户优先用掉。</p>
     *
     * @param query 分页参数 + 优惠券状态
     * @return 分页后的用户券视图对象（含券模板的规则信息）
     */
    @Override
    public PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query) {
        // 1.取当前登录用户
        Long userId = UserContext.getUserId();
        // 2.分页查询该用户的用户券：按状态过滤，有效期截止时间升序
        //    ⚠️ 「过期」是时间的函数，库里没有任务会把过期未使用的券改成 EXPIRED(3)，
        //    所以不能只按 status 等值过滤，否则：
        //      · 「未使用」Tab 会一直堆着早就过期的券
        //      · 「已过期」Tab 永远是空的
        //    这里按 Tab 语义做归一化：未使用 = 状态未使用且未到期；已过期 = 落库的 EXPIRED
        //    加上「状态未使用但时间已过」的那部分。
        //    注意排序仍用 term_end_time 升序（走 user_coupon 索引，且快过期的排前面）。
        OrderItem orderItem = new OrderItem();
        orderItem.setColumn("term_end_time");
        orderItem.setAsc(true);
        LocalDateTime now = LocalDateTime.now();
        UserCouponStatus status = UserCouponStatus.of(query.getStatus());
        LambdaQueryWrapper<UserCoupon> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(UserCoupon::getUserId, userId);
        if (status == UserCouponStatus.UNUSED) {
            wrapper.eq(UserCoupon::getStatus, UserCouponStatus.UNUSED)
                    .gt(UserCoupon::getTermEndTime, now);
        } else if (status == UserCouponStatus.EXPIRED) {
            wrapper.and(w -> w.eq(UserCoupon::getStatus, UserCouponStatus.EXPIRED)
                    .or(o -> o.eq(UserCoupon::getStatus, UserCouponStatus.UNUSED)
                            .le(UserCoupon::getTermEndTime, now)));
        } else {
            wrapper.eq(UserCoupon::getStatus, status);
        }
        Page<UserCoupon> page = baseMapper.selectPage(query.toMpPage(orderItem), wrapper);
        List<UserCoupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 3.批量回填券模板：先收集 couponId 去重，一次 selectBatchIds 查完，避免 N+1
        Set<Long> couponIds = records.stream()
                .map(UserCoupon::getCouponId)
                .collect(Collectors.toSet());
        Map<Long, Coupon> couponMap = couponMapper.selectBatchIds(couponIds).stream()
                .collect(Collectors.toMap(Coupon::getId, c -> c));
        // 限定范围的券带上分类id，前端才能显示「限 Java进阶」而不是一句「指定分类」
        List<Long> specificIds = couponMap.values().stream()
                .filter(c -> Boolean.TRUE.equals(c.getSpecific()))
                .map(Coupon::getId)
                .collect(Collectors.toList());
        Map<Long, List<Long>> scopeMap = couponScopeService.mapCategoryIdsByCouponIds(specificIds);
        // 4.按分页记录的顺序组装 VO
        //   注意：selectBatchIds 返回顺序不确定，不能直接拿它当结果集，否则前端卡片顺序会乱
        List<CouponVO> list = records.stream()
                .map(uc -> {
                    Coupon coupon = couponMap.get(uc.getCouponId());
                    if (coupon == null) return null;
                    CouponVO vo = BeanUtils.copyBean(coupon, CouponVO.class);
                    // 有效期必须用「用户券」的值覆盖模板值：
                    // termDays 型券的到期时间是领取那一刻才算出来的，模板上的 termBeginTime/termEndTime 是 null，
                    // 直接返回模板会让前端「我的券」显示不出真实到期时间
                    vo.setTermBeginTime(uc.getTermBeginTime());
                    vo.setTermEndTime(uc.getTermEndTime());
                    vo.setUserCouponId(uc.getId());
                    if (Boolean.TRUE.equals(coupon.getSpecific())) {
                        vo.setScopeCategoryIds(scopeMap.getOrDefault(coupon.getId(), Collections.emptyList()));
                    }
                    // 对外状态按「时间语义」归一化：库里仍是 UNUSED 但已过期的券，
                    // 在「已过期」Tab 里要报 EXPIRED，否则前端按状态渲染时会出现
                    // 「已过期 Tab 里的券标着未使用」这种自相矛盾的展示。
                    UserCouponStatus displayStatus = uc.getStatus();
                    if (displayStatus == UserCouponStatus.UNUSED
                            && uc.getTermEndTime() != null && uc.getTermEndTime().isBefore(now)) {
                        displayStatus = UserCouponStatus.EXPIRED;
                    }
                    vo.setUserCouponStatus(displayStatus == null ? null : displayStatus.getValue());
                    return vo;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return PageDTO.of(page, list);
    }

    @Override
    public void rollbackCouponReceive(UserCouponDTO uc) {
        // 1. 回滚 Bitmap（仅兑换码领取时有 codeId）
        if (uc.getCodeId() != null) {
            exchangeCodeService.updateExchangeMark(uc.getCodeId(), false);
        }
        // 2. 使用原子Lua脚本回滚：用户计数-1 + 库存+1
        if (uc.getCouponId() != null && uc.getUserId() != null) {
            String key1 = COUPON_CACHE_KEY_PREFIX + uc.getCouponId();
            String key2 = USER_COUPON_CACHE_KEY_PREFIX + uc.getCouponId();
            redisTemplate.execute(ROLLBACK_RECEIVE_COUPON_SCRIPT,
                    List.of(key1, key2), uc.getUserId().toString());
        }
        log.info("Redis回滚完成，codeId: {}, couponId: {}, userId: {}", uc.getCodeId(), uc.getCouponId(), uc.getUserId());
    }

    /**
     * 回滚失败的兜底：写进 Redis Set，等定时任务重试。
     * <p>用 Set 而不是 List 是为了天然去重 —— 同一个 DTO 反复落进来只会存一条。</p>
     */
    @Override
    public void addPendingRollback(UserCouponDTO uc) {
        if (uc == null) return;
        try {
            redisTemplate.opsForSet().add(COUPON_ROLLBACK_PENDING_KEY, JsonUtils.toJsonStr(uc));
        } catch (Exception e) {
            // 兜底通道本身也失败时只能记日志，人工介入
            log.error("回滚补偿记录写入失败，需人工处理：couponId={}, userId={}, codeId={}",
                    uc.getCouponId(), uc.getUserId(), uc.getCodeId(), e);
        }
    }

    @Override
    public int retryPendingRollback() {
        Set<String> members = redisTemplate.opsForSet().members(COUPON_ROLLBACK_PENDING_KEY);
        if (CollUtils.isEmpty(members)) return 0;
        int success = 0;
        for (String json : members) {
            UserCouponDTO uc;
            try {
                uc = JsonUtils.toBean(json, UserCouponDTO.class);
            } catch (Exception e) {
                // 数据坏了就丢掉，否则会一直卡在集合里
                log.error("回滚补偿记录解析失败，直接移除：{}", json, e);
                redisTemplate.opsForSet().remove(COUPON_ROLLBACK_PENDING_KEY, json);
                continue;
            }
            try {
                rollbackCouponReceive(uc);
                redisTemplate.opsForSet().remove(COUPON_ROLLBACK_PENDING_KEY, json);
                success++;
            } catch (Exception e) {
                log.error("回滚补偿重试失败，保留待下次重试：couponId={}, userId={}", uc.getCouponId(), uc.getUserId(), e);
            }
        }
        log.info("回滚补偿任务完成，本次成功 {} 条，待处理共 {} 条", success, members.size());
        return success;
    }

    /**
     * 核心校验与落库，重放和正常消费共用同一条链路，幂等由消费侧兜底
     */
    @Override
    public int replayParkedErrors() {
        int success = 0;
        while (true) {
            // RPOL 从队尾取，FIFO 消费；取到 null 说明存档已清空
            String json = redisTemplate.opsForList().rightPop(COUPON_ERROR_PARKED_KEY);
            if (json == null) break;
            UserCouponDTO uc;
            try {
                uc = JsonUtils.toBean(json, UserCouponDTO.class);
            } catch (Exception e) {
                // 坏数据直接丢：留着只会永远卡在存档里
                log.error("[COUPON-REPLAY] 存档记录解析失败，丢弃：{}", json, e);
                continue;
            }
            if (uc == null || uc.getUserId() == null || uc.getCouponId() == null) {
                log.error("[COUPON-REPLAY] 存档记录缺少 userId/couponId，丢弃：{}", json);
                continue;
            }
            try {
                replayOne(uc);
                success++;
                log.info("[COUPON-REPLAY] 重放成功：couponId={}, userId={}, codeId={}",
                        uc.getCouponId(), uc.getUserId(), uc.getCodeId());
            } catch (Exception e) {
                // 发送失败放回队尾（rightPush）并停止本轮：MQ 大概率不可用，
                // 继续循环只会空转；放队尾避免下轮立即弹出同一条形成自旋
                redisTemplate.opsForList().rightPush(COUPON_ERROR_PARKED_KEY, json);
                log.error("[COUPON-REPLAY] 重放失败，已放回存档，本轮终止：couponId={}, userId={}",
                        uc.getCouponId(), uc.getUserId(), e);
                break;
            }
        }
        log.info("[COUPON-REPLAY] 重放任务完成，本次成功 {} 条", success);
        return success;
    }

    /**
     * 重放单条消息：重新执行 Lua 预扣（与用户领券同一套脚本），再投递 MQ。
     * <p>幂等安全性说明：进入存档的消息必然已经回滚过 Redis 且 DB 无记录
     * （消费侧失败路径保证），所以预扣不会双扣；万一撞上「用户已重领」，
     * 消费侧的幂等键 + DB 回查会跳过，Redis 计数与 DB 记录数恰好对齐，也不会多扣。</p>
     */
    private void replayOne(UserCouponDTO uc) {
        Long couponId = uc.getCouponId();
        String key1 = COUPON_CACHE_KEY_PREFIX + couponId;
        String key2 = USER_COUPON_CACHE_KEY_PREFIX + couponId;
        if (uc.getCodeId() != null) {
            // 兑换码路径：EXCHANGE 脚本会顺带把 Bitmap 重新标记为已兑换（原回滚时已复位），
            // 返回 >10 为脚本反查出的 couponId，以它为准（和 uc.couponId 理论一致）
            String result = redisTemplate.execute(EXCHANGE_COUPON_SCRIPT,
                    List.of(COUPON_CODE_MAP_KEY, COUPON_CODE_RANGE_KEY),
                    String.valueOf(uc.getCodeId()), String.valueOf(uc.getCodeId() + 5000),
                    uc.getUserId().toString());
            long r = NumberUtils.parseLong(result);
            if (r < 10) {
                // 预扣失败 = 券已结束/已领完/码已失效，重放无意义，丢弃（Redis 已由回滚还原）
                log.warn("[COUPON-REPLAY] 兑换码预扣失败(code={})，丢弃：codeId={}, userId={}",
                        r, uc.getCodeId(), uc.getUserId());
                return;
            }
            couponId = r;
        } else {
            Long r = redisTemplate.execute(RECEIVE_COUPON_SCRIPT, List.of(key1, key2),
                    uc.getUserId().toString());
            int result = NumberUtils.null2Zero(r).intValue();
            if (result != 0) {
                // 预扣失败：券结束/领完/超限，丢弃 —— 库存已还原，用户重领也会被正常拦截
                log.warn("[COUPON-REPLAY] 领券预扣失败(code={})，丢弃：couponId={}, userId={}",
                        result, uc.getCouponId(), uc.getUserId());
                return;
            }
        }
        boolean sent = rabbitMqHelper.send(PROMOTION_EXCHANGE, COUPON_RECEIVE_KEY, uc);
        if (!sent) {
            // 发送失败：回滚刚才的预扣，再抛异常让外层把消息放回存档
            if (uc.getCodeId() != null) {
                redisTemplate.execute(ROLLBACK_EXCHANGE_COUPON_SCRIPT,
                        List.of(COUPON_CODE_MAP_KEY, USER_COUPON_CACHE_KEY_PREFIX + couponId,
                                COUPON_CACHE_KEY_PREFIX + couponId),
                        String.valueOf(uc.getCodeId()), uc.getUserId().toString());
            } else {
                redisTemplate.execute(ROLLBACK_RECEIVE_COUPON_SCRIPT, List.of(key1, key2),
                        uc.getUserId().toString());
            }
            throw new BizIllegalException("重放消息发送 MQ 失败");
        }
    }

    /**
     * 库存对账：两级比对，只告警不修复。
     * <ul>
     *   <li>DB 内部一致性：coupon.issue_num（消费侧事务里自增）必须等于 user_coupon 记录数 ——
     *       两者在同一个事务里变更，出现偏差说明有「提交了一半」的历史事故，需要人工核对。</li>
     *   <li>Redis 与 DB 一致性：缓存剩余 totalNum 应等于 coupon.total_num - issue_num ——
     *       领券时 Redis -1、消费时 issue_num +1，同步变更；偏差说明存在静默丢消息或重复回滚。
     *       缓存已过期回收的券（活动结束）跳过这一级。</li>
     * </ul>
     */
    @Override
    public int auditCouponInventory() {
        List<Coupon> coupons = couponMapper.selectList(null);
        if (CollUtils.isEmpty(coupons)) return 0;
        // 用 Set 而不是计数器：同一张券可能在「DB 口径」和「Redis 口径」各命中一次，
        // 计数会重复累加，导致告警文案里的「N 张券」大于实际张数，干扰人工排查。
        Set<Long> driftIds = new LinkedHashSet<>();
        for (Coupon coupon : coupons) {
            Long couponId = coupon.getId();
            // 1. DB 口径对账：发放数 vs 领取记录数
            Long dbCount = lambdaQuery().eq(UserCoupon::getCouponId, couponId).count();
            int issueNum = NumberUtils.null2Zero(coupon.getIssueNum());
            if (dbCount == null || dbCount != issueNum) {
                log.error("[COUPON-DRIFT] DB 发放数与领取记录数不一致：couponId={}, issue_num={}, count={}",
                        couponId, issueNum, dbCount);
                driftIds.add(couponId);
            }
            // 2. Redis 口径对账：缓存剩余 = 总量 - 已发放
            //    「缓存不存在」必须按状态分开看：
            //    已结束/暂停的券缓存早该被回收，跳过是对的；
            //    但发放中的券缺缓存是异常 —— 领券 Lua 第一步就是 exists 这个 key，
            //    缺了会让用户在列表里看得到券、点领取却提示「优惠券不存在」，
            //    而且这张券状态不会再变、issueCouponJob 只捞 status=2 也带不动它，
            //    过去正是被这里一句 continue 放过去了，所以必须计入漂移。
            Object redisTotal = redisTemplate.opsForHash().get(COUPON_CACHE_KEY_PREFIX + couponId, "totalNum");
            if (redisTotal == null) {
                if (coupon.getStatus() == CouponStatus.ISSUING) {
                    log.error("[COUPON-DRIFT] 发放中的券缺少库存缓存，领券会提示「优惠券不存在」：couponId={}", couponId);
                    driftIds.add(couponId);
                }
                continue;
            }
            int expect = NumberUtils.null2Zero(coupon.getTotalNum()) - issueNum;
            int actual;
            try {
                actual = Integer.parseInt(redisTotal.toString());
            } catch (NumberFormatException e) {
                log.error("[COUPON-DRIFT] Redis totalNum 不是合法数字：couponId={}, value={}", couponId, redisTotal);
                driftIds.add(couponId);
                continue;
            }
            if (expect != actual) {
                log.error("[COUPON-DRIFT] Redis 库存与 DB 推导值不一致：couponId={}, redis={}, expect={}",
                        couponId, actual, expect);
                driftIds.add(couponId);
            }
        }
        if (!driftIds.isEmpty()) {
            log.error("[COUPON-DRIFT] 对账完成，发现 {} 张券存在漂移，需人工排查（关键字 COUPON-DRIFT）：couponIds={}",
                    driftIds.size(), driftIds);
        } else {
            log.info("[COUPON-DRIFT] 对账完成，全部一致，共 {} 张券", coupons.size());
        }
        return driftIds.size();
    }

    /**
     * 核销优惠券
     * <p>用户下单使用时，将未使用的优惠券标记为已使用。
     * 仅对满足以下条件的优惠券进行核销：
     * <ul>
     *   <li>状态为 {@link UserCouponStatus#UNUSED 未使用}</li>
     *   <li>当前时间在优惠券有效期内（termBeginTime ~ termEndTime）</li>
     * </ul>
     * 核销成功后，同步增加优惠券模板的已使用计数。</p>
     *
     * @param couponIds 需要核销的优惠券ID列表
     */
    @Override
    @Transactional
    public void writeOffCoupon(List<Long> couponIds) {
        if (CollUtils.isEmpty(couponIds)) return;
        Long userId = UserContext.getUserId();
        LocalDateTime now = LocalDateTime.now();
        // 1. 只取「当前用户 + 未使用 + 在有效期内」的用户券，快过期的排前面
        List<UserCoupon> userCoupons = lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .in(UserCoupon::getCouponId, couponIds)
                .eq(UserCoupon::getStatus, UserCouponStatus.UNUSED)
                // termBeginTime 允许为空（历史数据），只有明确没到生效时间才排除
                .and(w -> w.isNull(UserCoupon::getTermBeginTime).or().le(UserCoupon::getTermBeginTime, now))
                .gt(UserCoupon::getTermEndTime, now)
                .orderByAsc(UserCoupon::getTermEndTime)
                .list();
        if (CollUtils.isEmpty(userCoupons)) return;

        // 2. 同一张模板只核销一张。
        //    入参是「券模板 id」（DiscountServiceImpl 用的是 coupon.getId()），
        //    若按 couponId IN 批量更新，用户手里同模板的多张券会被一次性全部核销掉，
        //    相当于白白吞掉用户剩下的券。
        Map<Long, UserCoupon> picked = new LinkedHashMap<>();
        for (UserCoupon uc : userCoupons) {
            picked.putIfAbsent(uc.getCouponId(), uc);
        }
        List<Long> validIds = picked.values()
                .stream().map(UserCoupon::getId).collect(Collectors.toList());

        // 3. 带状态条件更新，重复提交/并发下不会重复核销
        boolean update = lambdaUpdate()
                .set(UserCoupon::getStatus, UserCouponStatus.USED)
                .set(UserCoupon::getUsedTime, now)
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, UserCouponStatus.UNUSED)
                .in(UserCoupon::getId, validIds)
                .update();
        if (!update) return;

        // 4. 逐个模板累加已使用数。
        //    原来是一条 `UPDATE coupon SET used_num = used_num + 1 WHERE id IN (...)`，
        //    IN 里有多少个 id 都只 +1，多券叠加时计数必然偏小。
        //    另外这里失败只记日志：核销状态已经改成功了，统计数不准不该让整笔下单回滚。
        for (Long couponId : picked.keySet()) {
            if (couponMapper.increaseUsedNumByOne(couponId) < 1) {
                log.warn("优惠券已使用计数自增失败（used_num 可能已等于 issue_num），couponId={}", couponId);
            }
        }
    }


    /**
     * 退款退还优惠券
     * <p>用户退款时，将已使用的优惠券恢复到可用状态。
     * 根据优惠券是否过期做不同处理：
     * <ul>
     *   <li>未过期的优惠券 → 状态恢复为 {@link UserCouponStatus#UNUSED 未使用}，清空使用时间</li>
     *   <li>已过期的优惠券 → 状态改为 {@link UserCouponStatus#EXPIRED 已过期}，清空使用时间</li>
     * </ul>
     * 同时扣减优惠券模板的已使用计数（回退 -1）。</p>
     *
     * @param couponIds 需要退还的优惠券ID列表
     */
    @Override
    @Transactional
    public void refundCoupon(List<Long> couponIds) {
        if (CollUtils.isEmpty(couponIds)) return;
        Long userId = UserContext.getUserId();
        // 1. 只取当前用户「已使用」的券，最近使用的排前面
        List<UserCoupon> usedCoupons = lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .in(UserCoupon::getCouponId, couponIds)
                .eq(UserCoupon::getStatus, UserCouponStatus.USED)
                .orderByDesc(UserCoupon::getUsedTime)
                .list();
        if (CollUtils.isEmpty(usedCoupons)) return;

        // 2. 同一模板只退一张：否则用户历史用掉的同模板券会被一起退回来
        Map<Long, UserCoupon> picked = new LinkedHashMap<>();
        for (UserCoupon uc : usedCoupons) {
            picked.putIfAbsent(uc.getCouponId(), uc);
        }

        // 3. 按是否过期分别处理
        LocalDateTime now = LocalDateTime.now();
        List<Long> validIds = new ArrayList<>();
        List<Long> expiredIds = new ArrayList<>();
        for (UserCoupon uc : picked.values()) {
            if (uc.getTermEndTime() != null && now.isAfter(uc.getTermEndTime())) {
                expiredIds.add(uc.getId());
            } else {
                validIds.add(uc.getId());
            }
        }
        if (!validIds.isEmpty()) {
            lambdaUpdate()
                    .in(UserCoupon::getId, validIds)
                    .eq(UserCoupon::getStatus, UserCouponStatus.USED)
                    .set(UserCoupon::getStatus, UserCouponStatus.UNUSED)
                    .set(UserCoupon::getUsedTime, null)
                    .update();
        }
        if (!expiredIds.isEmpty()) {
            lambdaUpdate()
                    .in(UserCoupon::getId, expiredIds)
                    .eq(UserCoupon::getStatus, UserCouponStatus.USED)
                    .set(UserCoupon::getStatus, UserCouponStatus.EXPIRED)
                    .set(UserCoupon::getUsedTime, null)
                    .update();
        }

        // 4. 逐个模板扣减已使用数（同样不能用 IN 批量 -1）
        for (Long couponId : picked.keySet()) {
            if (couponMapper.decreaseUsedNumByOne(couponId) < 1) {
                log.warn("优惠券已使用计数扣减失败（used_num 可能已为 0），couponId={}", couponId);
            }
        }
    }


    /**
     * 批量查询优惠券的规则描述信息
     * <p>根据优惠券ID列表，查询每张优惠券对应的折扣规则文本描述，
     * 例如："满100减20"、"每满50减10，上限100"、"无门槛抵50元"等。</p>
     *
     * @param couponIds 优惠券ID列表
     * @return 优惠券规则描述字符串列表，顺序与传入的ID列表一致
     */
    @Override
    public List<String> queryCouponRules(List<Long> couponIds) {
        // 参数校验：ID列表为空则直接返回空集合
        if (CollUtils.isEmpty(couponIds)) return Collections.emptyList();
        // 批量查询数据库，获取优惠券实体
        List<Coupon> coupons = couponMapper.selectBatchIds(couponIds);
        // 查询结果为空则返回空集合
        if (CollUtils.isEmpty(coupons)) return Collections.emptyList();
        // 通过策略模式，根据不同的折扣类型生成对应的规则描述
        return coupons
                .stream()
                .map(coupon -> DiscountStrategy.getDiscount(coupon.getDiscountType()).getRule(coupon))
                .collect(Collectors.toList());
    }
}