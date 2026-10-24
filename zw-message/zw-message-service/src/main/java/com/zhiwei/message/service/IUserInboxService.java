package com.zhiwei.message.service;

import com.zhiwei.message.domain.dto.UserInboxDTO;
import com.zhiwei.message.domain.dto.UserInboxFormDTO;
import com.zhiwei.api.dto.user.UserDTO;
import com.zhiwei.message.domain.query.UserInboxQuery;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.message.domain.po.NoticeTemplate;
import com.zhiwei.message.domain.po.UserInbox;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 用户通知记录 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-19
 */
public interface IUserInboxService extends IService<UserInbox> {

    void saveNoticeToInbox(NoticeTemplate noticeTemplate, List<UserDTO> users);

    PageDTO<UserInboxDTO> queryUserInBoxesPage(UserInboxQuery query);

    Long sentMessageToUser(UserInboxFormDTO userInboxFormDTO);
}
