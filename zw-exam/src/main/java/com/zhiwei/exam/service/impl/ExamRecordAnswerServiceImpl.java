package com.zhiwei.exam.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.exam.domain.po.ExamRecordAnswer;
import com.zhiwei.exam.mapper.ExamRecordAnswerMapper;
import com.zhiwei.exam.service.IExamRecordAnswerService;
import org.springframework.stereotype.Service;

/**
 * 考试答题记录 服务实现类
 */
@Service
public class ExamRecordAnswerServiceImpl extends ServiceImpl<ExamRecordAnswerMapper, ExamRecordAnswer> implements IExamRecordAnswerService {

}