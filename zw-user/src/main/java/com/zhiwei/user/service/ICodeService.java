package com.zhiwei.user.service;

public interface ICodeService {

    /**
     * 发送手机验证码。
     *
     * <p>返回本次生效的验证码，供调用方在「演示环境」回显给前端用。
     * 正常的生产调用方应当忽略返回值 —— 验证码只能走短信通道到达用户手机。
     *
     * <p>本方法同时承担限流职责，两级：
     * ① 同一手机号 60 秒冷却；② 同一客户端 IP 24 小时总量上限。
     * 注册页是匿名可调的，没有 ② 的话任何人都能换着号码批量刷短信。
     *
     * @param phone    目标手机号
     * @param clientIp 客户端真实 IP，用于 IP 维度限流；取不到时传 null（跳过该级限流）
     * @return 本次生效的验证码
     */
    String sendVerifyCode(String phone, String clientIp);

    /**
     * 校验验证码。不通过直接抛 BadRequestException。
     */
    void verifyCode(String phone, String code);
}
