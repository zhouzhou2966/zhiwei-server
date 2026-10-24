package com.zhiwei.auth.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.auth.domain.po.LoginRecord;

/**
 * <p>
 * 登录信息记录表 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-07-12
 */
public interface ILoginRecordService extends IService<LoginRecord> {

    void saveAsync(LoginRecord record);

    void loginSuccess(String cellphone, Long userId);
}
