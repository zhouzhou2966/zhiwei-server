package com.zhiwei.user.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.user.domain.query.UserPageQuery;
import com.zhiwei.user.domain.vo.StaffVO;

/**
 * <p>
 * 员工详情表 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-07-12
 */
public interface IStaffService {
    PageDTO<StaffVO> queryStaffPage(UserPageQuery pageQuery);
}
