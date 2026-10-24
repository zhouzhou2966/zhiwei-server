package com.zhiwei.auth.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.auth.domain.po.Role;

import java.util.List;

/**
 * <p>
 * 角色表 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-06-16
 */
public interface IRoleService extends IService<Role> {

    boolean exists(Long roleId);
    boolean exists(List<Long> roleIds);

    void deleteRole(Long id);
}
