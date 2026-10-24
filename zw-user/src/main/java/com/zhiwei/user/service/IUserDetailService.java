package com.zhiwei.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.user.domain.po.UserDetail;
import com.zhiwei.user.domain.query.UserPageQuery;
import com.zhiwei.common.enums.UserType;

import java.util.List;

/**
 * <p>
 * 教师详情表 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-15
 */
public interface IUserDetailService extends IService<UserDetail> {

    UserDetail queryById(Long userId);

    List<UserDetail> queryByIds(List<Long> ids);

    Page<UserDetail> queryUserDetailByPage(UserPageQuery pageQuery, UserType type);
}
