package com.zhiwei.exam.service.impl;

import com.zhiwei.exam.domain.po.QuestionDetail;
import com.zhiwei.exam.mapper.QuestionDetailMapper;
import com.zhiwei.exam.service.IQuestionDetailService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 题目 服务实现类
 * </p>
 *
 * @author zhiwei
 * @since 2022-09-02
 */
@Service
public class QuestionDetailServiceImpl extends ServiceImpl<QuestionDetailMapper, QuestionDetail> implements IQuestionDetailService {

}
