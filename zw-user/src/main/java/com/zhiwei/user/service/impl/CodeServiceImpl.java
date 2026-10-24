package com.zhiwei.user.service.impl;

import com.zhiwei.message.domain.enums.SmsTemplate;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.RandomUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.message.api.client.AsyncSmsClient;
import com.zhiwei.message.domain.dto.SmsInfoDTO;
import com.zhiwei.user.service.ICodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.zhiwei.api.constants.SmsConstants.VERIFY_CODE_PARAM_NAME;
import static com.zhiwei.common.constants.ErrorInfo.Msg.INVALID_VERIFY_CODE;
import static com.zhiwei.user.constants.UserConstants.*;
import static com.zhiwei.user.constants.UserErrorInfo.Msg.CELL_PHONE_REQUIRED;
import static com.zhiwei.user.constants.UserErrorInfo.Msg.CODE_SEND_IP_LIMIT;
import static com.zhiwei.user.constants.UserErrorInfo.Msg.CODE_SEND_TOO_FREQUENT;
import static com.zhiwei.user.constants.UserErrorInfo.Msg.CODE_VERIFY_FAIL_LIMIT;
import static com.zhiwei.user.constants.UserErrorInfo.Msg.SMS_SEND_FAIL;

@Slf4j
@Service
public class CodeServiceImpl implements ICodeService {

    /**
     * 验证码「比对 + 删除」原子脚本。
     *
     * <p>为什么必须原子：原来的写法是 GET → Java 里 compare → DELETE 三步。
     * 三步之间有窗口，两个并发请求带着同一个码进来，可以都读到还没被删的码、
     * 都判 200 → 同一个验证码被「双消费」（register 即可并发注册两次，
     * 最终靠 DB 唯一键兜底，但用户看到的是 500 而不是友好的 400）。
     *
     * <p>比对放在服务端脚本里做而不是 GETDEL + Java 比较：
     * GETDEL 是无条件删除，会把「比对失败」的请求也顺手把码删掉，
     * 等于让攻击者随便试错就能顶掉合法用户的码（DoS）。
     * 这个脚本保留原语义 —— 只有比对成功才删除。
     */
    private static final DefaultRedisScript<Long> CONSUME_IF_MATCH =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                            "redis.call('DEL', KEYS[1]) " +
                            "return 1 " +
                            "else " +
                            "return 0 " +
                            "end",
                    Long.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private AsyncSmsClient asyncSmsClient;

    @Override
    public String sendVerifyCode(String phone, String clientIp) {
        // 0.手机号不能为空 —— 否则 key 会退化成固定前缀，所有人的码互相覆盖
        if (StringUtils.isBlank(phone)) {
            throw new BadRequestException(CELL_PHONE_REQUIRED);
        }
        // 1.发送频率限制：同一手机号 60 秒内只允许发一次。
        //   用 SETNX + TTL 做「冷却窗口」，天然是原子的 —— 不需要额外加锁，
        //   并发重复点击时只有一个请求能抢到 key，其余直接拒绝。
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(USER_VERIFY_CODE_LIMIT_KEY + phone, "1", USER_VERIFY_CODE_SEND_INTERVAL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BadRequestException(CODE_SEND_TOO_FREQUENT);
        }
        // 2.IP 维度日总量限制。
        //   刻意排在手机号冷却之后：连点同一个号码会被 ① 直接挡掉，不会白耗 IP 配额。
        checkIpDailyLimit(clientIp);

        String key = USER_VERIFY_CODE_KEY + phone;
        // 3.查看code是否存在（冷却期内不可能存在，但冷却窗口外可能还在 5 分钟有效期内，
        //   这时复用旧码，避免用户连点两次拿到两个不同的码）
        String code = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(code)) {
            // 4.生成随机验证码
            code = RandomUtils.randomNumbers(4);
            // 5.保存到redis
            stringRedisTemplate.opsForValue().set(key, code, USER_VERIFY_CODE_TTL);
        }
        // 6.发送短信。
        //   失败必须把发码冷却删掉：SETNX（第 1 步）已经烧掉了 60s 冷却，
        //   但用户一条短信都没收到（Broker 挂了 / MQ 消费者挂了 / 消息被 nack），
        //   此时若保留冷却，用户白等 60s 才能重试，属于「系统错误用户买单」。
        //   验证码本体不删：重试时直接复用同一个码，用户收到后仍然有效。
        try {
            log.debug("发送短信验证码：{}", code);
            SmsInfoDTO info = new SmsInfoDTO();
            info.setPhones(CollUtils.singletonList(phone));
            info.setTemplateCode(SmsTemplate.VERIFY_CODE.toString());
            Map<String, String> params = new HashMap<>(1);
            params.put(VERIFY_CODE_PARAM_NAME, code);
            info.setTemplateParams(params);
            asyncSmsClient.sendMessage(info);
        } catch (RuntimeException e) {
            log.error("验证码消息发送失败，phone={}", phone, e);
            stringRedisTemplate.delete(USER_VERIFY_CODE_LIMIT_KEY + phone);
            throw new BadRequestException(SMS_SEND_FAIL);
        }
        // 7.返回本次生效的验证码，由 controller 决定是否回显（仅演示环境）
        return code;
    }

    /**
     * 同一 IP 24 小时内的发送总量限制（固定窗口）。
     *
     * <p>为什么用「日期进 key」而不是给一个固定 key 设 TTL：
     * 固定 key 一旦 TTL 因异常没设上，这个 IP 就被永久锁死了；
     * 日期进 key 后，跨天自动换 key，最坏情况只是残留一个孤儿 key。
     *
     * <p>为什么不用 Lua 脚本做原子 INCR+EXPIRE：
     * 这里先在 SETNX 里带上 TTL，再 INCR，两步之间即使进程挂掉，
     * key 也已经带上了过期时间，不存在「无 TTL 的永久 key」这个漏洞，
     * 因此没必要引入 Lua 增加维护成本。
     */
    private void checkIpDailyLimit(String clientIp) {
        if (StringUtils.isBlank(clientIp)) {
            // 取不到 IP 就跳过这一级限流：宁可少一层防护，
            // 也不能因为解析不出 IP 就把所有正常用户全部拒掉。
            log.warn("未能获取客户端 IP，本次跳过验证码 IP 限流");
            return;
        }
        String key = USER_VERIFY_CODE_IP_LIMIT_KEY + clientIp + ":" + LocalDate.now();
        // SETNX 只在首次成功，不会重置已存在 key 的过期时间 —— 正好符合固定窗口语义
        stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "0", USER_VERIFY_CODE_IP_LIMIT_TTL);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count > USER_VERIFY_CODE_IP_DAILY_LIMIT) {
            throw new BadRequestException(CODE_SEND_IP_LIMIT);
        }
    }

    @Override
    public void verifyCode(String phone, String code) {
        // 0.空值先挡掉，不能只依赖下面的 equals 比对。
        //   ⚠️ 这里是个真坑：hutool 的 StrUtil.equals(null, null) 返回 true，
        //   而 zw-common 的 StringUtils 是直接 extends StrUtil 的。
        //   于是当 Redis 里的验证码已过期（cacheCode=null）、请求又没带 code 字段
        //   （code=null）时，equals 判定为「相等」→ 校验直接通过 →
        //   不带验证码就能注册任意手机号 / 重置他人密码。
        //   StudentFormDTO 上也没有 @NotBlank，所以必须在业务层堵住。
        if (StringUtils.isBlank(phone) || StringUtils.isBlank(code)) {
            throw new BadRequestException(INVALID_VERIFY_CODE);
        }
        String failKey = USER_VERIFY_CODE_FAIL_KEY + phone;
        // 1.先看是否已被锁定。这一步必须排在比对之前 ——
        //   否则「锁定」形同虚设：达到上限后仍能继续试探。
        String fails = stringRedisTemplate.opsForValue().get(failKey);
        if (fails != null && Long.parseLong(fails) >= USER_VERIFY_CODE_MAX_FAIL) {
            throw new BadRequestException(CODE_VERIFY_FAIL_LIMIT);
        }
        // 比对 + 删除 一气呵成（Lua 原子执行，详见脚本定义处注释）。
        // 返回 1 = 比对成功且已删除；返回 0 = 比对失败（码不存在或不对 —— 两种都算失败，
        // 无需也没必要先 GET 一次区分，Lua 内部已经看过码了）。
        // 只有比对成功才删除，保留「失败不删码」的语义，防止试错顶掉合法用户的码。
        Long matched = stringRedisTemplate.execute(
                CONSUME_IF_MATCH,
                Collections.singletonList(USER_VERIFY_CODE_KEY + phone),
                code);
        if (matched == null || matched != 1L) {
            recordVerifyFailure(failKey);
            throw new BadRequestException(INVALID_VERIFY_CODE);
        }
        // 3.校验通过：清空错误计数（码已由脚本原子删除，无需再 delete 一次）
        stringRedisTemplate.delete(failKey);
    }

    /**
     * 记录一次验证失败，达到上限时抛锁定异常。
     * 计数 key 在首次创建时就带有 TTL，不会出现「无 TTL 永久 key」；
     * 且已存在时不重置 TTL，避免攻击者靠持续试错把计数窗口无限延长。
     */
    private void recordVerifyFailure(String failKey) {
        stringRedisTemplate.opsForValue()
                .setIfAbsent(failKey, "0", USER_VERIFY_CODE_FAIL_TTL);
        Long count = stringRedisTemplate.opsForValue().increment(failKey);
        if (count != null && count >= USER_VERIFY_CODE_MAX_FAIL) {
            throw new BadRequestException(CODE_VERIFY_FAIL_LIMIT);
        }
    }
}
