package com.zhiwei.exam.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.domain.query.PageQuery;
import com.zhiwei.exam.domain.dto.ExamStartFormDTO;
import com.zhiwei.exam.domain.dto.ExamSubmitFormDTO;
import com.zhiwei.exam.domain.po.ExamRecord;
import com.zhiwei.exam.domain.vo.ExamDetailVO;
import com.zhiwei.exam.domain.vo.ExamRecordPageVO;
import com.zhiwei.exam.domain.vo.ExamRecordVO;

import java.util.List;

/**
 * 考试记录 服务类
 */
public interface IExamRecordService extends IService<ExamRecord> {

    /**
     * 开始考试，获取考题
     *
     * @param startFormDTO 开始考试表单
     * @return 考试记录及题目列表（不含答案）
     */
    ExamRecordVO startExam(ExamStartFormDTO startFormDTO);

    /**
     * 提交考试答案并自动判分
     *
     * @param submitFormDTO 提交表单
     * @return 批阅结果（逐题对错、得分），前端直接拿去渲染答题卡
     */
    List<ExamDetailVO> submitExam(ExamSubmitFormDTO submitFormDTO);

    /**
     * 分页查询考试记录
     *
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    PageDTO<ExamRecordPageVO> queryExamRecordPage(PageQuery pageQuery);

    /**
     * 查询考试记录详情
     *
     * @param id 考试记录id
     * @return 考试详情列表
     */
    List<ExamDetailVO> queryExamDetail(Long id);

    /**
     * 查询当前用户「已考完」的小节 id 集合
     * <p>课程目录页拿它给练习/考试节点打「已完成」的勾。</p>
     *
     * @return 已交卷（status=1）的小节 id 列表
     */
    List<Long> queryFinishedSectionIds();
}