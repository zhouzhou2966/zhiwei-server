package com.zhiwei.user.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiwei.api.client.trade.TradeClient;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.enums.UserType;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.UnauthorizedException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.RandomUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.user.constants.UserConstants;
import com.zhiwei.user.domain.dto.StudentFormDTO;
import com.zhiwei.user.domain.dto.UserPasswordFormDTO;
import com.zhiwei.user.domain.po.User;
import com.zhiwei.user.domain.po.UserDetail;
import com.zhiwei.user.domain.query.UserPageQuery;
import com.zhiwei.user.domain.vo.StudentPageVo;
import com.zhiwei.user.service.ICodeService;
import com.zhiwei.user.service.IStudentService;
import com.zhiwei.user.service.IUserDetailService;
import com.zhiwei.user.service.IUserService;
import com.zhiwei.user.support.ProfileChangeNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.zhiwei.user.constants.UserErrorInfo.Msg.CELL_PHONE_REQUIRED;
import static com.zhiwei.user.constants.UserErrorInfo.Msg.PHONE_ALREADY_EXISTS;
import static com.zhiwei.user.constants.UserErrorInfo.Msg.USER_ID_NOT_EXISTS;

/**
 * <p>
 * 学员详情表 服务实现类
 * </p>
 *
 * @author zhiwei
 * @since 2022-07-12
 */
@Service
@RequiredArgsConstructor
public class StudentServiceImpl implements IStudentService {

    private final IUserService userService;
    private final IUserDetailService detailService;
    private final ICodeService codeService;
    private final TradeClient tradeClient;
    private final ProfileChangeNotifier profileChangeNotifier;

    @Override
    @Transactional
    public void saveStudent(StudentFormDTO studentFormDTO) {
        // 1.新增用户账号
        User user = new User();
        user.setCellPhone(studentFormDTO.getCellPhone());
        user.setPassword(studentFormDTO.getPassword());
        user.setType(UserType.STUDENT);
        userService.addUserByPhone(user, studentFormDTO.getCode());

        // 2.新增学员详情
        UserDetail student = new UserDetail();
        student.setId(user.getId());
        student.setName(RandomUtils.randomString(8));
        student.setRoleId(UserConstants.STUDENT_ROLE_ID);
        detailService.save(student);
    }

    @Override
    public void updateMyPassword(StudentFormDTO studentFormDTO) {
        userService.updatePasswordByPhone(
                studentFormDTO.getCellPhone(), studentFormDTO.getCode(), studentFormDTO.getPassword()
        );
    }

    @Override
    public void updateMyLoginPassword(UserPasswordFormDTO formDTO) {
        // 用户 id 只能从登录上下文取，不接受前端传入（防越权改别人密码）
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException(USER_ID_NOT_EXISTS);
        }
        userService.updatePassword(userId, formDTO.getOldPassword(), formDTO.getNewPassword());
    }

    @Override
    public void updateMyInfo(StudentFormDTO studentFormDTO) {
        // 关键：用户 id 只能从登录上下文取，绝不能接受前端传入的 id，
        // 否则任何登录用户都能改别人的昵称/头像（越权）。
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException(USER_ID_NOT_EXISTS);
        }
        // user_detail.id 就是 user.id（见 saveStudent 里的 student.setId(user.getId())）
        UserDetail detail = new UserDetail();
        detail.setId(userId);
        detail.setName(studentFormDTO.getName());
        detail.setIcon(studentFormDTO.getIcon());
        detail.setGender(studentFormDTO.getGender());
        // MyBatis-Plus 的 updateById 默认只更新非 null 字段，
        // 所以前端没传的字段（比如只改头像时的昵称）不会被清空。
        detailService.updateById(detail);
        // 通知订阅方（zw-message）：失效它们进程内的用户资料缓存，并把新昵称头像推给在线客户端，
        // 否则用户换完头像回聊天页看到的还是旧头像，且刷新浏览器也不会变。
        // 带上 name/icon：订阅方在 Redis 监听线程里没有用户上下文，回查 user-service 会被判 401。
        profileChangeNotifier.notifyChanged(userId, studentFormDTO.getName(), studentFormDTO.getIcon());
    }

    @Override
    @Transactional
    public void updateMyPhone(StudentFormDTO studentFormDTO) {
        // 用户 id 只从登录上下文取，不接受前端传入（否则任何登录用户都能换掉别人的登录手机号）
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException(USER_ID_NOT_EXISTS);
        }
        String cellPhone = studentFormDTO.getCellPhone();
        if (StringUtils.isBlank(cellPhone)) {
            throw new BadRequestException(CELL_PHONE_REQUIRED);
        }
        // 1.校验发到「新手机号」上的验证码。
        //   为什么不验旧号：验旧号要证明「是本人」，但本项目短信通道 AK 已清空、
        //   旧号根本收不到码，演示时这条路走不通。折中为「已登录 + 新号可控」，
        //   代价是拿到 token 就能换手机号 —— 生产环境应当补上旧号校验。
        codeService.verifyCode(cellPhone, studentFormDTO.getCode());
        // 2.查重：手机号是登录凭据（登录时可直接用手机号），必须全局唯一。
        //   要排除自己 —— 否则用户点「修改」却填回原号时，会被自己这条记录挡住。
        Long count = userService.lambdaQuery()
                .eq(User::getCellPhone, cellPhone)
                .ne(User::getId, userId)
                .count();
        if (count > 0) {
            throw new BadRequestException(PHONE_ALREADY_EXISTS);
        }
        // 3.落库（username 与 cell_phone 一起改，两者必须保持一致）
        userService.updatePhoneById(userId, cellPhone);
    }

    @Override
    public void updateMyEmail(String email) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new UnauthorizedException(USER_ID_NOT_EXISTS);
        }
        String normalized = StringUtils.isBlank(email) ? null : email.trim();
        // 这里必须用 UpdateWrapper 显式 set 字段。
        // updateById 走的是「只更新实体里非 null 的字段」策略，传 null 会被直接忽略，
        // 于是「解绑邮箱」会退化成一条什么都不改的空操作，用户点了没反应。
        detailService.lambdaUpdate()
                .eq(UserDetail::getId, userId)
                .set(UserDetail::getEmail, normalized)
                .update();
    }

    @Override
    public PageDTO<StudentPageVo> queryStudentPage(UserPageQuery query) {
        // 1.分页条件
        Page<UserDetail> page  =  detailService.queryUserDetailByPage(query, UserType.STUDENT);
        List<UserDetail> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        // 2.查询购买的课程数量
        List<Long> stuIds = records.stream().map(UserDetail::getId).collect(Collectors.toList());
        Map<Long, Integer> numMap = tradeClient.countEnrollCourseOfStudent(stuIds);

        // 3.处理vo
        List<StudentPageVo> list = new ArrayList<>(records.size());
        for (UserDetail r : records) {
            StudentPageVo v = BeanUtils.toBean(r, StudentPageVo.class);
            list.add(v);
            v.setCourseAmount(numMap.get(r.getId()));
        }
        return new PageDTO<>(page.getTotal(), page.getPages(), list);
    }
}
