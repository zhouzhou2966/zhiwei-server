package com.zhiwei.auth.service;

import com.zhiwei.api.dto.user.LoginFormDTO;

/**
 * <p>
 * 账号表，平台内所有用户的账号、密码信息 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-06-16
 */
public interface IAccountService{

    String login(LoginFormDTO loginFormDTO, boolean isStaff);

    void logout();

    String refreshToken(String refreshToken);
}
