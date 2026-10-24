package com.zhiwei.user.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.api.client.auth.AuthClient;
import com.zhiwei.api.dto.auth.RoleDTO;
import com.zhiwei.api.dto.user.LoginFormDTO;
import com.zhiwei.api.dto.user.UserDTO;
import com.zhiwei.common.domain.dto.LoginUserDTO;
import com.zhiwei.common.enums.UserType;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.ForbiddenException;
import com.zhiwei.common.exceptions.UnauthorizedException;
import com.zhiwei.common.utils.AssertUtils;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.user.domain.dto.UserFormDTO;
import com.zhiwei.user.domain.po.User;
import com.zhiwei.user.domain.po.UserDetail;
import com.zhiwei.user.domain.vo.UserDetailVO;
import com.zhiwei.user.enums.UserStatus;
import com.zhiwei.user.mapper.UserMapper;
import com.zhiwei.user.service.ICodeService;
import com.zhiwei.user.service.IUserDetailService;
import com.zhiwei.user.service.IUserService;
import com.zhiwei.user.support.ProfileChangeNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.zhiwei.user.constants.UserConstants.*;
import static com.zhiwei.user.constants.UserErrorInfo.Msg.*;


/**
 * <p>
 * 学员用户表 服务实现类
 * </p>
 *
 * @author zhiwei
 * @since 2022-06-28
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ICodeService codeService;
    @Autowired
    private AuthClient authClient;
    @Autowired
    private IUserDetailService detailService;
    @Autowired
    private ProfileChangeNotifier profileChangeNotifier;

    @Override
    public LoginUserDTO queryUserDetail(LoginFormDTO loginDTO, boolean isStaff) {
        // 1.判断登录方式
        Integer type = loginDTO.getType();
        User user = null;
        // 2.用户名和密码登录
        if (type == 1) {
            user = loginByPw(loginDTO);
        }
        // 3.验证码登录
        if (type == 2) {
            user = loginByVerifyCode(loginDTO.getCellPhone(), loginDTO.getPassword());
        }
        // 4.错误的登录方式
        if (user == null) {
            throw new BadRequestException(ILLEGAL_LOGIN_TYPE);
        }
        // 5.判断用户类型与登录方式是否匹配
        if (isStaff ^ user.getType() != UserType.STUDENT) {
            throw new BadRequestException(isStaff ? "非管理端用户" : "非学生端用户");
        }
        // 6.封装返回
        LoginUserDTO userDTO = new LoginUserDTO();
        userDTO.setUserId(user.getId());
        userDTO.setRoleId(handleRoleId(user));
        return userDTO;
    }

    @Override
    public void resetPassword(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        AssertUtils.isTrue(updateById(user), USER_ID_NOT_EXISTS);
    }

    @Override
    public UserDetailVO myInfo() {
        // 1.获取登录用户id
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return null;
        }
        // 2.查询用户
        UserDetail userDetail = detailService.queryById(userId);
        AssertUtils.isNotNull(userDetail, USER_ID_NOT_EXISTS);
        // 3.封装vo
        UserType type = userDetail.getType();
        // 3.1.基本信息
        UserDetailVO vo = BeanUtils.toBean(userDetail, UserDetailVO.class);
        // 3.2.详情信息
        switch (type) {
            case STAFF:
                RoleDTO roleDTO = authClient.queryRoleById(userDetail.getRoleId());
                vo.setRoleName(roleDTO == null ? "" : roleDTO.getName());
                break;
            case STUDENT:
                vo.setRoleName(STUDENT_ROLE_NAME);
                break;
            case TEACHER:
                vo.setRoleName(TEACHER_ROLE_NAME);
                break;
            default:
                break;
        }
        return vo;
    }

    @Override
    public void addUserByPhone(User user, String code) {
        // 0.参数校验（放在这里而不是 StudentFormDTO 上加 bean validation）：
        //   StudentFormDTO 是「注册 / 改密码 / 改资料 / 改手机号」多个流程共用的 DTO，
        //   给它加 @NotBlank 会把「改资料」流程一并要求传密码，只能在这一层按入口拦。
        //   不拦的后果：password=null 会一路走到 passwordEncoder.encode(null) 抛异常 → 500；
        //   手机号不带格式校验能让任意字符串过掉前端后直调注册。
        validateRegisterParams(user.getCellPhone(), user.getPassword());
        // 1.验证码校验
        codeService.verifyCode(user.getCellPhone(), code);
        // 2.判断手机号是否存在
        Long count = lambdaQuery().eq(User::getCellPhone, user.getCellPhone()).count();
        if (count > 0) {
            throw new BadRequestException(PHONE_ALREADY_EXISTS);
        }
        // 3.加密密码
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        // 4.新增
        user.setUsername(user.getCellPhone());
        save(user);
    }

    @Override
    public void updatePasswordByPhone(String cellPhone, String code, String password) {
        // 0.与注册入口同样的参数校验：密码为空会直接 500
        validateRegisterParams(cellPhone, password);
        // 1.验证码校验
        codeService.verifyCode(cellPhone, code);
        // 2.查询用户
        User oldUser = lambdaQuery().eq(User::getCellPhone, cellPhone).one();
        if (oldUser == null) {
            // 手机号不存在
            throw new BadRequestException(PHONE_NOT_EXISTS);
        }
        // 2.修改密码
        User user = new User();
        // 这里必须用 oldUser.getId()：user 是上面刚 new 出来的空对象，getId() 恒为 null，
        // 拿它去 updateById 只会生成 `WHERE id = null`，一条也更新不到，改密码必然静默失败。
        user.setId(oldUser.getId());
        user.setPassword(passwordEncoder.encode(password));
        updateById(user);
    }

    @Override
    public void updatePhoneById(Long id, String cellPhone) {
        // 1.1.判断是否需要修改手机号
        if (StringUtils.isNotBlank(cellPhone)) {
            // 1.2.需要修改，封装数据
            User user = new User();
            user.setId(id);
            // ⚠️ 只改 cell_phone，不再同步改 username —— 登录名与手机号已解耦。
            // 原实现是 user.setUsername(cellPhone)，即「换手机号 = 换登录名」。
            // 因为注册时 username 恰好等于手机号，这个副作用长期没暴露；
            // 但只要用户换过一次手机号，他原来的登录名就凭空消失了，用旧登录名登不进去
            // （demo 账号 jack/rose/admin 用的是昵称式登录名，一改手机号就中招）。
            user.setCellPhone(cellPhone);
            // 1.3.修改
            updateById(user);
        }
    }

    @Override
    @Transactional
    public Long saveUser(UserDTO userDTO) {
        UserType type = UserType.of(userDTO.getType());
        // 1.保存用户基本信息
        User user = new User();
        user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setCellPhone(userDTO.getCellPhone());
        user.setUsername(userDTO.getCellPhone());
        user.setType(type);
        save(user);
        // 2.新增详情
        UserDetail detail = BeanUtils.toBean(userDTO, UserDetail.class);
        detail.setId(user.getId());
        detail.setType(type);
        if (type == UserType.TEACHER) {
            detail.setRoleId(TEACHER_ROLE_ID);
        } else {
            if (userDTO.getRoleId() == null) {
                throw new BadRequestException("员工角色信息不能为空");
            }
        }
        detailService.save(detail);
        return user.getId();
    }

    @Override
    @Transactional
    public void updateUser(UserDTO userDTO) {
        // 1.如果传递了手机号，则修改手机号
        String cellphone = userDTO.getCellPhone();
        if (StringUtils.isNotBlank(cellphone)) {
            User user = new User();
            user.setId(userDTO.getId());
            // 同 updatePhoneById：只改手机号，不同步改 username（登录名与手机号解耦）。
            // 管理端改用户资料时同样不该顺手把别人的登录名换掉。
            user.setCellPhone(cellphone);
            updateById(user);
        }
        // 2.修改详情
        UserDetail detail = BeanUtils.toBean(userDTO, UserDetail.class);
        detail.setType(null);
        detailService.updateById(detail);
        // 管理端改资料同样要通知缓存失效（昵称/头像改了，聊天里不能还显示旧的）；
        // 带上 name/icon 供订阅方直接推送（订阅线程没有用户上下文，回查会 401）
        profileChangeNotifier.notifyChanged(detail.getId(), userDTO.getName(), userDTO.getIcon());
    }

    @Override
    public void updateUserWithPassword(UserFormDTO userDTO) {
        // 用户 id 一律从登录上下文取。
        // 原来 detail 的主键靠前端传（UserFormDTO 继承自 UserDTO，带 id 字段），
        // 前端不传时 updateById 会生成 `WHERE id = null` 一条也更新不到（静默失效），
        // 传了别人的 id 又能改到别人头上（越权）—— 两头都不对。
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException(USER_ID_NOT_EXISTS);
        }
        // 1.尝试更新密码（新密码与原密码都传了才走这一步）
        String pw = userDTO.getPassword();
        String oldPw = userDTO.getOldPassword();
        // 注意两个判断必须是不同变量：若写成 isNotBlank(pw) && isNotBlank(pw)，
        // 等价于只判断「新密码非空」。此时原密码为 null，
        // BCrypt 的 matches(null, hash) 返回 false，于是抛「用户登录信息错误」——
        // 报错原因和真实原因（压根没传原密码）对不上，排查时极具误导性。
        if (StringUtils.isNotBlank(pw) && StringUtils.isNotBlank(oldPw)) {
            // 具体校验与落库统一走 updatePassword，避免「改密码」的逻辑在两个入口各写一份
            updatePassword(userId, oldPw, pw);
        }
        // 2.更新用户详情
        UserDetail detail = BeanUtils.toBean(userDTO, UserDetail.class);
        detail.setId(userId);
        detail.setRoleId(null);
        detail.setType(null);
        // 只有确实带了资料字段才去 update user_detail。
        // 「只改密码」时请求体里只有 oldPassword/password，detail 除主键外全是 null，
        // 而 updateById 会把 null 字段整体跳过 —— SET 子句为空会生成
        // `UPDATE user_detail SET WHERE id = ?` 这种语法非法的 SQL，接口直接 500。
        // （UserDetail 里没有任何 @TableField(fill=...) 字段，MetaObjectHandler 也补不上东西。）
        if (hasProfileField(detail)) {
            detailService.updateById(detail);
            // 同上：这个入口也能改昵称/头像，一样要通知缓存失效 + 推送
            profileChangeNotifier.notifyChanged(userId, userDTO.getName(), userDTO.getIcon());
        }
    }

    @Override
    public void updatePassword(Long userId, String oldPassword, String newPassword) {
        // 1.查当前用户（拿库里的 BCrypt 密文比对）
        User user = getById(userId);
        if (user == null) {
            throw new UnauthorizedException(USER_ID_NOT_EXISTS);
        }
        // 2.校验原密码。
        //    这里必须是 400 而不是 401 —— 前端的 401 拦截器语义是「凭据失效」，
        //    会清本地 token、弹「登录超时」并把人踢到登录页；
        //    用户只是把原密码打错一个字，不该被登出。
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BadRequestException(INVALID_UN_OR_PW);
        }
        // 3.落库新密码（只更新 password 一个字段）
        User update = new User();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        updateById(update);
    }

    /**
     * 判断这次请求是否真的带了「用户资料」字段（昵称/头像/性别等）。
     * 这些字段全为 null 时不能拿去 updateById，原因见调用处注释。
     */
    private boolean hasProfileField(UserDetail detail) {
        return StringUtils.isNotBlank(detail.getName())
                || StringUtils.isNotBlank(detail.getIcon())
                || StringUtils.isNotBlank(detail.getEmail())
                || StringUtils.isNotBlank(detail.getQq())
                || StringUtils.isNotBlank(detail.getJob())
                || StringUtils.isNotBlank(detail.getIntro())
                || StringUtils.isNotBlank(detail.getPhoto())
                || StringUtils.isNotBlank(detail.getProvince())
                || StringUtils.isNotBlank(detail.getCity())
                || StringUtils.isNotBlank(detail.getDistrict())
                || detail.getGender() != null;
    }

    public User loginByPw(LoginFormDTO loginDTO) {
        // 1.数据校验
        String username = loginDTO.getUsername();
        String cellPhone = loginDTO.getCellPhone();
        if (StrUtil.isBlank(username) && StrUtil.isBlank(cellPhone)) {
            throw new BadRequestException(INVALID_UN);
        }
        // 2.查询用户
        User user;
        if (StrUtil.isNotBlank(username) && StrUtil.isNotBlank(cellPhone)) {
            // 2.1.两个字段都传了：维持改动前的原语义 —— 用户名与手机号都要匹配
            user = lambdaQuery()
                    .eq(User::getUsername, username)
                    .eq(User::getCellPhone, cellPhone)
                    .one();
        } else if (StrUtil.isNotBlank(username)) {
            // 2.2.只传了用户名。门户的登录框只有一个输入框，用户填的可能是用户名也可能是手机号，
            //     所以这里真正兑现输入框上「请输入用户名或手机号」的承诺。
            //     先按 username 精确命中 —— 这是改动前的唯一行为，绝不能退化；
            //     没命中再按手机号找。
            //     刻意不写成 `username = ? OR cell_phone = ?`：那种写法在
            //     「A 的 username 恰好等于 B 的 cell_phone」时会命中两行，
            //     MyBatis-Plus 的 .one() 会直接抛 TooManyResultsException；
            //     分两步查的优先级是确定的（用户名优先），不会因数据撞车而报错。
            user = lambdaQuery().eq(User::getUsername, username).one();
            if (user == null) {
                user = lambdaQuery().eq(User::getCellPhone, username).one();
            }
        } else {
            // 2.3.只传了手机号（管理端等显式调用方）
            user = lambdaQuery().eq(User::getCellPhone, cellPhone).one();
        }
        AssertUtils.isNotNull(user, INVALID_UN_OR_PW);
        // 3.校验是否禁用
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new ForbiddenException(USER_FROZEN);
        }
        // 4.校验密码
        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BadRequestException(INVALID_UN_OR_PW);
        }

        return user;
    }

    /**
     * 注册 / 短信改密共用的入参校验。失败一律 400，不放行到下游。
     */
    private void validateRegisterParams(String cellPhone, String password) {
        if (StringUtils.isBlank(cellPhone) || !cellPhone.trim().matches(PHONE_REGEX)) {
            throw new BadRequestException(PHONE_FORMAT_INVALID);
        }
        if (StringUtils.isBlank(password)) {
            throw new BadRequestException(PASSWORD_REQUIRED);
        }
    }

    private Long handleRoleId(User user) {
        Long roleId = 0L;
        switch (user.getType()) {
            case STUDENT:
                roleId = STUDENT_ROLE_ID;
                break;
            case TEACHER:
                roleId = TEACHER_ROLE_ID;
                break;
            case STAFF:
                UserDetail detail = detailService.getById(user.getId());
                roleId = detail.getRoleId();
                break;
        }
        return roleId;
    }

    public User loginByVerifyCode(String phone, String code) {
        // 1.校验验证码
        codeService.verifyCode(phone, code);
        // 2.根据手机号查询
        User user = lambdaQuery().eq(User::getCellPhone, phone).one();
        if (user == null) {
            throw new BadRequestException(PHONE_NOT_EXISTS);
        }
        // 3.校验是否禁用
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new ForbiddenException(USER_FROZEN);
        }
        return user;
    }
}
