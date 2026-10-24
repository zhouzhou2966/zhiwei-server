package com.zhiwei.learning.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.learning.domian.dto.QuestionFormDTO;
import com.zhiwei.learning.domian.po.InteractionQuestion;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.learning.domian.query.QuestionAdminPageQuery;
import com.zhiwei.learning.domian.query.QuestionPageQuery;
import com.zhiwei.learning.domian.vo.QuestionAdminVO;
import com.zhiwei.learning.domian.vo.QuestionVO;

import javax.validation.Valid;

/**
 * <p>
 * 互动提问的问题表 服务类
 * </p>
 *
 * @author author
 * @since 2026-05-10
 */
public interface IInteractionQuestionService extends IService<InteractionQuestion> {
    // 添加问题
    void addQuestion(@Valid QuestionFormDTO questionFormDTO);

    //  分页查询问题
    PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query);

    // 查询问题详情
    QuestionVO queryQuestionById(Long id);

    // 管理端分页查询问题
    PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query);

    // 修改问题
    void updateQuestion(@Valid QuestionFormDTO questionFormDTO, Long id);

    // 删除我的问题
    void removeMyQuestion(Long id);

    // 修改问题隐藏状态
    void updateQuestionHidden(Long id, Boolean hidden);

    // 管理端查询问题详情
    QuestionAdminVO adminQueryQuestionById(Long id);
}
