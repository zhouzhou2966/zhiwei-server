package com.zhiwei.user.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.user.domain.query.UserPageQuery;
import com.zhiwei.user.domain.vo.TeacherPageVO;

/**
 * <p>
 * 教师详情表 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-07-12
 */
public interface ITeacherService{
    PageDTO<TeacherPageVO> queryTeacherPage(UserPageQuery pageQuery);

}
