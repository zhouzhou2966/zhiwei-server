package com.zhiwei.user.constants;

public interface UserErrorInfo {
    interface Msg {
        String INVALID_UN_OR_PW = "用户登录信息错误";
        String INVALID_UN = "用户名或手机号不能为空";
        String INVALID_USER_TYPE = "无效的用户类型";
        String PHONE_NOT_EXISTS = "手机号不存在";
        String ILLEGAL_LOGIN_TYPE = "无效的登录方式";
        String USER_ID_NOT_EXISTS = "用户id不存在";

        String USER_FROZEN = "账号已冻结";
        String PHONE_ALREADY_EXISTS = "手机号已存在";
        String INVALID_USER_STATUS = "用户状态不正确";

        String CODE_SEND_TOO_FREQUENT = "验证码发送过于频繁，请稍后再试";
        String CODE_SEND_IP_LIMIT = "当前网络发送验证码次数过多，请明天再试";
        String CELL_PHONE_REQUIRED = "手机号不能为空";
        // 注意措辞：锁定后「重新获取验证码」并不能解除锁定
        // （失败计数 key 独立于验证码 key，TTL 10 分钟比验证码的 5 分钟更长），
        // 所以不能提示「请重新获取验证码」—— 那会误导用户白折腾一轮。
        String CODE_VERIFY_FAIL_LIMIT = "验证码错误次数过多，请稍后再试";

        String SMS_SEND_FAIL = "验证码发送失败，请稍后重试";
        String PHONE_FORMAT_INVALID = "手机号格式不正确";
        String PASSWORD_REQUIRED = "密码不能为空";
    }
}
