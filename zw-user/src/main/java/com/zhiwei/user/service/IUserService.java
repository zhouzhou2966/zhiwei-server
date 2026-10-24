package com.zhiwei.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.api.dto.user.LoginFormDTO;
import com.zhiwei.api.dto.user.UserDTO;
import com.zhiwei.common.domain.dto.LoginUserDTO;
import com.zhiwei.user.domain.dto.UserFormDTO;
import com.zhiwei.user.domain.po.User;
import com.zhiwei.user.domain.vo.UserDetailVO;

/**
 * <p>
 * 学员用户表 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-06-28
 */
public interface IUserService extends IService<User> {
    LoginUserDTO queryUserDetail(LoginFormDTO loginDTO, boolean isStaff);

    void resetPassword(Long userId);

    UserDetailVO myInfo();

    void addUserByPhone(User user, String code);

    void updatePasswordByPhone(String cellPhone, String code, String password);

    /**
     * 修改指定用户的手机号。
     * 注意：这里只负责落库，不校验验证码、也不查占用 —— 调用方必须先把这两件事做完。
     * 只改 cell_phone，<b>不再同步改 username</b>：登录名与手机号已解耦，
     * 换手机号不会让用户原来的登录名失效（登录时用户名和手机号都能用）。
     */
    void updatePhoneById(Long id, String cellPhone);

    Long saveUser(UserDTO userDTO);

    void updateUser(UserDTO userDTO);

    void updateUserWithPassword(UserFormDTO userDTO);

    /**
     * 校验原密码后，把指定用户的登录密码改成新密码。
     * <p>
     * 只有「原密码正确」才会落库；原密码错误抛 {@code BadRequestException}（业务码 400），
     * 不能抛 401 —— 前端的 401 拦截器语义是「凭据失效」，会清 token 并把用户踢到登录页，
     * 而用户只是把原密码打错了一个字。
     */
    void updatePassword(Long userId, String oldPassword, String newPassword);
}
