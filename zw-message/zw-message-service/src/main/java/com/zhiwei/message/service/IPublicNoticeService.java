package com.zhiwei.message.service;

import com.zhiwei.message.domain.po.NoticeTemplate;
import com.zhiwei.message.domain.po.PublicNotice;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 公告消息模板 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-19
 */
public interface IPublicNoticeService extends IService<PublicNotice> {

    void saveNoticeOfTemplate(NoticeTemplate noticeTemplate);
}
