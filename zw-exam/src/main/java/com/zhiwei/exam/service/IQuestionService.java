package com.zhiwei.exam.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.api.dto.exam.QuestionDTO;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.exam.domain.dto.QuestionFormDTO;
import com.zhiwei.exam.domain.po.Question;
import com.zhiwei.exam.domain.query.QuestionPageQuery;
import com.zhiwei.exam.domain.vo.QuestionDetailVO;
import com.zhiwei.exam.domain.vo.QuestionPageVO;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 题目 服务类
 * </p>
 *
 * @author zhiwei
 * @since 2022-09-02
 */
public interface IQuestionService extends IService<Question> {

    void addQuestion(QuestionFormDTO questionFormDTO);

    void updateQuestion(QuestionFormDTO questionDTO);

    void deleteQuestionById(Long id);

    PageDTO<QuestionPageVO> queryQuestionByPage(QuestionPageQuery query);

    QuestionDetailVO queryQuestionDetailById(Long id);

    List<QuestionDTO> queryQuestionByIds(List<Long> ids);

    Map<Long, Integer> countQuestionNumOfCreater(List<Long> createrIds);

    List<QuestionDTO> queryQuestionByBizId(Long bizId);

    Boolean checkNameValid(String name);

    Map<Long, Integer> queryQuestionScores(List<Long> ids);
}
