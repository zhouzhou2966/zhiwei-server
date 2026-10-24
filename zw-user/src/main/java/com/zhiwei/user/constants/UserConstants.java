package com.zhiwei.user.constants;

import java.time.Duration;

public interface UserConstants {
    String DEFAULT_PASSWORD = "123456";

    Long STUDENT_ROLE_ID = 2L;
    String STUDENT_ROLE_NAME = "学生";

    Long TEACHER_ROLE_ID = 3L;
    String TEACHER_ROLE_NAME = "教师";

    // 手机号格式校验正则（与前端 Register.vue 用的是同一套号段口径）。
    // 为什么后端必须再校验一遍：前端正则只挡普通用户的手误，
    // 直调 API / 脚本调用完全不带前端，没有这道校验，
    // 任意字符串都能拿到「验证码」并最终落库。
    String PHONE_REGEX = "^1[3-9]\\d{9}$";

    // 验证码的Redis key前缀
    String USER_VERIFY_CODE_KEY = "sms:user:code:phone:";
    // 验证码有效期，5分钟
    Duration USER_VERIFY_CODE_TTL = Duration.ofMinutes(5);

    // 同一手机号的发送冷却时间。
    // 没有它的话，任何人拿到 token 就能无限刷这个接口 —— 短信通道是按条计费的，
    // 被人脚本循环调用既烧钱又会被运营商判定为骚扰号。
    String USER_VERIFY_CODE_LIMIT_KEY = "sms:user:code:limit:";
    Duration USER_VERIFY_CODE_SEND_INTERVAL = Duration.ofSeconds(60);

    // 同一 IP 的发送总量限制。
    // 为什么手机号冷却不够：它只能挡住「同一个号码被连点」，挡不住「换着号码刷」——
    // 攻击者拿一批号码循环调用，每个号码都是首次发送，冷却形同虚设。IP 维度才能挡住批量刷。
    // key 里带日期构成「固定窗口」，天然按天归零：即使 TTL 因异常没设上，
    // 也只是残留一个孤儿 key，不会把某个 IP 永久锁死。
    String USER_VERIFY_CODE_IP_LIMIT_KEY = "sms:user:code:ip:";
    long USER_VERIFY_CODE_IP_DAILY_LIMIT = 20L;
    // 25 小时而不是 24 小时：保证 key 一定活过它所属的那一天，
    // 否则 23:59 建立的 key 会在当天结束前就过期，当天的计数被提前清掉。
    Duration USER_VERIFY_CODE_IP_LIMIT_TTL = Duration.ofHours(25);

    // 验证码「校验失败次数」限制。
    // 为什么需要：验证码是 4 位纯数字，只有 1 万种组合。没有失败上限时，
    // 攻击者可以在 5 分钟有效期内把 0000~9999 逐个试完，等于验证码形同虚设。
    // 计数 key 独立于验证码 key —— 验证码被消费或自然过期后计数仍要保留，
    // 否则攻击者只要等验证码过期就能重置计数、接着爆破。
    String USER_VERIFY_CODE_FAIL_KEY = "sms:user:code:fail:";
    long USER_VERIFY_CODE_MAX_FAIL = 5L;
    // 10 分钟：刻意比验证码 5 分钟的有效期长，保证「验证码已过期但计数还在」，
    // 中间不留空窗。
    Duration USER_VERIFY_CODE_FAIL_TTL = Duration.ofMinutes(10);
}
