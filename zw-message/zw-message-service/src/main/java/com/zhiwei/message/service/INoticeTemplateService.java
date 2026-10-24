package com.zhiwei.message.service;

import com.zhiwei.message.domain.dto.NoticeTemplateDTO;
import com.zhiwei.message.domain.dto.NoticeTemplateFormDTO;
import com.zhiwei.message.domain.query.NoticeTemplatePageQuery;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.message.domain.po.NoticeTemplate;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 通知模板 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-19
 */
public interface INoticeTemplateService extends IService<NoticeTemplate> {

    Long saveNoticeTemplate(NoticeTemplateFormDTO noticeTemplateFormDTO);

    void updateNoticeTemplate(NoticeTemplateFormDTO noticeTemplateFormDTO);

    PageDTO<NoticeTemplateDTO> queryNoticeTemplates(NoticeTemplatePageQuery pageQuery);

    NoticeTemplateDTO queryNoticeTemplate(Long id);

    NoticeTemplate queryByCode(String code);
}
