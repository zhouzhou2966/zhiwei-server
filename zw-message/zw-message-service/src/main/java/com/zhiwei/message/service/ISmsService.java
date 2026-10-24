package com.zhiwei.message.service;

import com.zhiwei.api.dto.sms.SmsInfoDTO;
import com.zhiwei.api.dto.user.UserDTO;
import com.zhiwei.message.domain.po.NoticeTemplate;

import java.util.List;

public interface ISmsService {
    void sendMessageByTemplate(NoticeTemplate noticeTemplate, List<UserDTO> users);

    void sendMessage(SmsInfoDTO smsInfoDTO);

    void sendMessageAsync(SmsInfoDTO smsInfoDTO);
}
