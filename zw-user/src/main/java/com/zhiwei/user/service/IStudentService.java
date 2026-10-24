package com.zhiwei.user.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.user.domain.dto.StudentFormDTO;
import com.zhiwei.user.domain.dto.UserPasswordFormDTO;
import com.zhiwei.user.domain.query.UserPageQuery;
import com.zhiwei.user.domain.vo.StudentPageVo;

/**
 * <p>
 * 学员详情表 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-07-12
 */
public interface IStudentService {

    void saveStudent(StudentFormDTO studentFormDTO);

    void updateMyPassword(StudentFormDTO studentFormDTO);

    /**
     * 修改「当前登录学员」的登录密码（原密码 + 新密码）。
     * 与 {@link #updateMyPassword(StudentFormDTO)} 的区别：那个是「用短信验证码重置」，
     * 这个是「已知原密码时自助修改」。用户 id 同样只从登录上下文取。
     */
    void updateMyLoginPassword(UserPasswordFormDTO formDTO);

    /**
     * 修改「当前登录学员」的基本信息（昵称、头像、性别）。
     * 注意：只允许改自己，用户 id 从登录上下文取，不接受前端传入。
     */
    void updateMyInfo(StudentFormDTO studentFormDTO);

    /**
     * 修改「当前登录学员」的手机号。
     * 会校验发到新手机号上的短信验证码，并检查新号是否已被他人占用。
     * 用户 id 同样只从登录上下文取。
     */
    void updateMyPhone(StudentFormDTO studentFormDTO);

    /**
     * 修改「当前登录学员」的邮箱，传空表示解绑。
     * 用户 id 同样只从登录上下文取。
     */
    void updateMyEmail(String email);

    PageDTO<StudentPageVo> queryStudentPage(UserPageQuery pageQuery);
}
