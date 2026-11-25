package com.zhiwei.exam.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.api.client.course.CourseClient;
import com.zhiwei.api.dto.course.CourseSimpleInfoDTO;
import com.zhiwei.api.dto.course.SectionInfoDTO;
import com.zhiwei.api.dto.exam.QuestionDTO;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.domain.query.PageQuery;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.NumberUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.exam.domain.dto.ExamStartFormDTO;
import com.zhiwei.exam.domain.dto.ExamSubmitFormDTO;
import com.zhiwei.exam.domain.po.ExamRecord;
import com.zhiwei.exam.domain.po.ExamRecordAnswer;
import com.zhiwei.exam.domain.po.Question;
import com.zhiwei.exam.domain.po.QuestionDetail;
import com.zhiwei.exam.domain.vo.ExamDetailVO;
import com.zhiwei.exam.domain.vo.ExamRecordPageVO;
import com.zhiwei.exam.domain.vo.ExamRecordVO;
import com.zhiwei.exam.domain.vo.ExamStatsVO;
import com.zhiwei.exam.domain.vo.QuestionDetailVO;
import com.zhiwei.exam.mapper.ExamRecordMapper;
import com.zhiwei.exam.service.IExamRecordAnswerService;
import com.zhiwei.exam.service.IExamRecordService;
import com.zhiwei.exam.service.IQuestionDetailService;
import com.zhiwei.exam.service.IQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 考试记录 服务实现类
 * </p>
 * <p>说明：考试与练习共用同一套表。{@code exam_record.type} 由前端传入
 * （前端算的是 {@code 目录type - 1}：2-考试，1-练习）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamRecordServiceImpl extends ServiceImpl<ExamRecordMapper, ExamRecord> implements IExamRecordService {

    /** 题目类型：判断题 */
    private static final int QUESTION_TYPE_JUDGE = 4;
    /** 题目类型：主观题（无法自动判分） */
    private static final int QUESTION_TYPE_SUBJECTIVE = 5;
    /** 考试状态：未完成（点过开始考试但还没交卷） */
    private static final int STATUS_UNFINISHED = 0;
    /** 考试状态：已完成 */
    private static final int STATUS_FINISHED = 1;
    /** 统计头里的日期只到「天」：10/22 这种足够，带上时分反而是噪音 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("M/d");
    /** 类型：练习 */
    private static final int TYPE_PRACTICE = 1;
    /** 类型：考试 */
    private static final int TYPE_EXAM = 2;
    /** 正确率放大倍数：8550 表示 85.5% */
    private static final int RATE_SCALE = 10000;

    private final IQuestionService questionService;
    private final IQuestionDetailService questionDetailService;
    private final IExamRecordAnswerService examRecordAnswerService;
    private final CourseClient courseClient;

    /**
     * 开始考试：按小节取题 + 建考试记录
     * <p>取题走 {@code question_biz}，bizId 就是小节的目录 id。
     * 返回给前端的题目<b>必须剔除正确答案和解析</b>，否则等于开卷。</p>
     */
    /**
     * 开始考试：按小节取题
     * <p><b>这里不落库</b>——考试记录等「提交试卷」时才创建。否则用户点一下「开始考试」
     * 就会在「我的考试」里留下一条 0 分的空记录。</p>
     * <p>同一用户在同一小节只能考一次，已交过卷直接拒绝。</p>
     */
    @Override
    public ExamRecordVO startExam(ExamStartFormDTO dto) {
        Long userId = UserContext.getUserId();
        int type = dto.getType() == null ? TYPE_EXAM : dto.getType();
        // 1.只能考一次：已交过卷就不允许再进入答题
        assertNotExamined(userId, dto.getSectionId(), type);
        // 2.按小节id取题（question_biz.biz_id = sectionId）
        List<QuestionDTO> questions = questionService.queryQuestionByBizId(dto.getSectionId());
        if (CollUtils.isEmpty(questions)) {
            throw new BizIllegalException("该小节下还没有配置题目");
        }
        // 3.只组装返回，不写库
        ExamRecordVO vo = new ExamRecordVO()
                .setCourseId(dto.getCourseId())
                .setSectionId(dto.getSectionId())
                .setType(type)
                .setQuestionCount(questions.size())
                .setTotalScore(questions.stream()
                        .mapToInt(q -> NumberUtils.null2Zero(q.getScore()))
                        .sum())
                .setStatus(STATUS_UNFINISHED);
        vo.setQuestions(questions.stream().map(this::toExamQuestion).collect(Collectors.toList()));
        return vo;
    }

    /**
     * 校验「同一用户 + 同一小节 + 同一类型」是否已经考过。
     * <p>只要存在一条已交卷（status=1）的记录就不允许再来，即「只能考一次」。</p>
     */
    private void assertNotExamined(Long userId, Long sectionId, Integer type) {
        Long count = lambdaQuery()
                .eq(ExamRecord::getUserId, userId)
                .eq(ExamRecord::getSectionId, sectionId)
                .eq(ExamRecord::getType, type)
                .eq(ExamRecord::getStatus, STATUS_FINISHED)
                .count();
        if (count != null && count > 0) {
            throw new BizIllegalException("该考试已参加过，不能重复考试");
        }
    }

    /**
     * QuestionDTO -> QuestionDetailVO，并清空 answer / analysis。
     * <p>QuestionDTO.type 是 String，QuestionDetailVO.type 是 Integer，这里显式转一次。</p>
     */
    private QuestionDetailVO toExamQuestion(QuestionDTO q) {
        QuestionDetailVO vo = new QuestionDetailVO()
                .setId(q.getId())
                .setName(q.getName())
                .setDifficulty(q.getDifficulty())
                .setScore(q.getScore())
                .setOptions(q.getOptions())
                // 考试中不下发答案和解析
                .setAnswer(null)
                .setAnalysis(null);
        try {
            vo.setType(q.getType() == null ? null : Integer.valueOf(q.getType().trim()));
        } catch (NumberFormatException e) {
            log.warn("题目类型不是数字，questionId={}, type={}", q.getId(), q.getType());
        }
        return vo;
    }

    /**
     * 提交考试答案并自动判分
     * <p>客观题（单选/多选/不定项/判断）按 {@code question_detail.answer} 自动比对；
     * 主观题（type=5）机器判不了，统一给 0 分、{@code comment} 留空，等人工批阅。</p>
     *
     * @return 批阅结果（与「查询考试详情」同一结构），前端直接拿去渲染答题卡
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ExamDetailVO> submitExam(ExamSubmitFormDTO dto) {
        Long userId = UserContext.getUserId();
        int type = dto.getType() == null ? TYPE_EXAM : dto.getType();
        // 1.只能考一次：已交过卷就不允许再提交（前端可能绕过，这里必须兜住）
        assertNotExamined(userId, dto.getSectionId(), type);
        List<ExamSubmitFormDTO.AnswerFormDTO> details = dto.getExamDetails();
        if (CollUtils.isEmpty(details)) {
            throw new BizIllegalException("答题内容不能为空");
        }
        // 2.批量查题目和题目详情（一次查完，避免逐题 N+1）
        List<Long> questionIds = details.stream()
                .map(ExamSubmitFormDTO.AnswerFormDTO::getQuestionId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Question> questionMap = questionService.listByIds(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        Map<Long, QuestionDetail> detailMap = questionDetailService.listByIds(questionIds).stream()
                .collect(Collectors.toMap(QuestionDetail::getId, d -> d));
        // 3.逐题判分
        List<ExamRecordAnswer> answerList = new ArrayList<>(details.size());
        int gotScore = 0, totalScore = 0, correctCount = 0;
        for (ExamSubmitFormDTO.AnswerFormDTO item : details) {
            Question question = questionMap.get(item.getQuestionId());
            if (question == null) {
                log.warn("提交的题目不存在，questionId={}", item.getQuestionId());
                continue;
            }
            QuestionDetail detail = detailMap.get(item.getQuestionId());
            // 前端每题都带了 questionType，优先用它（题库的 type 万一没维护也能判）
            Integer qType = item.getQuestionType() != null ? item.getQuestionType() : question.getType();
            boolean correct = detail != null && judge(qType, detail.getAnswer(), item.getAnswer());
            int fullScore = NumberUtils.null2Zero(question.getScore());
            totalScore += fullScore;
            if (correct) {
                correctCount++;
                gotScore += fullScore;
            }
            // examRecordId 要等考试记录创建后才有，先不设，最后统一回填
            answerList.add(new ExamRecordAnswer()
                    .setQuestionId(question.getId())
                    .setAnswer(item.getAnswer())
                    .setIsCorrect(correct ? 1 : 0)
                    .setScore(correct ? fullScore : 0));
        }
        if (CollUtils.isEmpty(answerList)) {
            throw new BizIllegalException("答题内容不能为空");
        }
        // 4.创建考试记录 —— 提交试卷时才落库，直接就是「已完成」状态
        int correctRate = (int) Math.round(correctCount * RATE_SCALE * 1.0 / answerList.size());
        ExamRecord record = new ExamRecord()
                .setLessonId(dto.getLessonId() == null ? 0L : dto.getLessonId())
                .setCourseId(dto.getCourseId())
                .setSectionId(dto.getSectionId())
                .setUserId(userId)
                .setType(type)
                .setQuestionCount(answerList.size())
                .setTotalScore(totalScore)
                .setScore(gotScore)
                .setCorrectRate(correctRate)
                // 用时由前端从进入答题页开始计时（后端没落库，就没有 createTime 可以相减）
                .setDuration(dto.getDuration() == null || dto.getDuration() < 0 ? 0 : dto.getDuration())
                .setStatus(STATUS_FINISHED)
                .setFinishTime(LocalDateTime.now());
        save(record);
        // 5.回填考试记录id后落库答题记录
        answerList.forEach(a -> a.setExamRecordId(record.getId()));
        examRecordAnswerService.saveBatch(answerList);
        // 6.返回批阅结果
        return queryExamDetail(record.getId());
    }

    /**
     * 客观题自动判分。
     * <ul>
     *   <li>单选/多选/不定项：题库答案是选项序号（从 1 开始，多选逗号分隔），
     *       排序后比对，避免前端提交顺序不同被误判</li>
     *   <li>判断题：前端传 {@code true}/{@code false}，题库里 {@code "1"} 代表正确、其他代表错误</li>
     *   <li>主观题：无法自动判分，直接返回 false（0 分，等人工批阅）</li>
     * </ul>
     */
    private boolean judge(Integer questionType, String correctAnswer, String userAnswer) {
        if (questionType == null || StringUtils.isBlank(correctAnswer) || StringUtils.isBlank(userAnswer)) {
            return false;
        }
        if (questionType == QUESTION_TYPE_SUBJECTIVE) {
            return false;
        }
        if (questionType == QUESTION_TYPE_JUDGE) {
            // 兼容前端传 true/false，也兼容传 1/2 的情况
            boolean userSaidTrue = "true".equalsIgnoreCase(userAnswer.trim()) || "1".equals(userAnswer.trim());
            boolean correctIsTrue = "1".equals(correctAnswer.trim());
            return userSaidTrue == correctIsTrue;
        }
        return normalizeAnswer(userAnswer).equals(normalizeAnswer(correctAnswer));
    }

    /** 把 "3,1,2" / "1, 2" 这类答案统一成升序、无空格的 "1,2,3" 再比较 */
    private String normalizeAnswer(String answer) {
        return Arrays.stream(answer.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * 分页查询我的考试记录
     * <p>只返回<b>已交卷</b>（status=1）的记录：点「开始考试」时会先落一条 status=0 的记录
     * 用于中途退出后续考复用，那种未完成的记录不该出现在列表里（否则会看到一堆 0 分「--」的垃圾行）。</p>
     * <p>课程名从课程服务批量查（一次 Feign）；章节名课程服务没有批量接口，
     * 只能逐个查，但一页通常只有 10 条，可以接受。</p>
     */
    @Override
    public PageDTO<ExamRecordPageVO> queryExamRecordPage(PageQuery query) {
        Long userId = UserContext.getUserId();
        // 1.分页查当前用户已完成的考试记录，按创建时间倒序
        Page<ExamRecord> page = lambdaQuery()
                .eq(ExamRecord::getUserId, userId)
                .eq(ExamRecord::getStatus, STATUS_FINISHED)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<ExamRecord> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 2.批量查课程名
        Set<Long> courseIds = records.stream()
                .map(ExamRecord::getCourseId)
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());
        Map<Long, String> courseNameMap = new HashMap<>();
        if (CollUtils.isNotEmpty(courseIds)) {
            List<CourseSimpleInfoDTO> courses = courseClient.getSimpleInfoList(courseIds);
            if (CollUtils.isNotEmpty(courses)) {
                courses.forEach(c -> courseNameMap.put(c.getId(), c.getName()));
            }
        }
        // 3.逐个查章节名（去重后查，同一小节只查一次）
        Map<Long, String> sectionNameMap = new HashMap<>();
        for (ExamRecord r : records) {
            Long sectionId = r.getSectionId();
            if (sectionId == null || sectionNameMap.containsKey(sectionId)) {
                continue;
            }
            try {
                SectionInfoDTO info = courseClient.sectionInfo(sectionId);
                sectionNameMap.put(sectionId, info == null ? null : info.getName());
            } catch (Exception e) {
                // 跨服务查询失败不能拖垮列表，记日志降级为 null
                log.warn("查询小节名称失败，sectionId={}", sectionId, e);
                sectionNameMap.put(sectionId, null);
            }
        }
        // 4.组装 VO
        List<ExamRecordPageVO> list = records.stream().map(r -> {
            ExamRecordPageVO vo = BeanUtils.copyBean(r, ExamRecordPageVO.class);
            vo.setCourseName(courseNameMap.get(r.getCourseId()));
            vo.setSectionName(sectionNameMap.get(r.getSectionId()));
            // 前端列表用 commitTime 显示「考试时间」
            vo.setCommitTime(r.getFinishTime());
            return vo;
        }).collect(Collectors.toList());
        return PageDTO.of(page, list);
    }

    /**
     * 查询考试记录详情（逐题批阅结果，含正确答案和解析）
     * <p>只有已提交的考试才该看到答案，所以这里带 answer/analysis 是安全的。</p>
     */
    @Override
    public List<ExamDetailVO> queryExamDetail(Long id) {
        // 1.查答题记录
        List<ExamRecordAnswer> answers = examRecordAnswerService.lambdaQuery()
                .eq(ExamRecordAnswer::getExamRecordId, id)
                .list();
        if (CollUtils.isEmpty(answers)) {
            return Collections.emptyList();
        }
        // 2.批量查题目和题目详情
        List<Long> questionIds = answers.stream()
                .map(ExamRecordAnswer::getQuestionId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Question> questionMap = questionService.listByIds(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));
        Map<Long, QuestionDetail> detailMap = questionDetailService.listByIds(questionIds).stream()
                .collect(Collectors.toMap(QuestionDetail::getId, d -> d));
        // 3.组装
        return answers.stream().map(a -> {
            ExamDetailVO vo = new ExamDetailVO();
            vo.setAnswer(a.getAnswer());
            vo.setComment(a.getComment());
            vo.setCorrect(a.getIsCorrect() != null && a.getIsCorrect() == 1);
            vo.setScore(a.getScore());
            Question question = questionMap.get(a.getQuestionId());
            if (question != null) {
                ExamDetailVO.QuestionVO qv = new ExamDetailVO.QuestionVO();
                qv.setId(question.getId());
                qv.setName(question.getName());
                qv.setType(question.getType());
                qv.setScore(question.getScore());
                qv.setDifficulty(question.getDifficulty());
                QuestionDetail detail = detailMap.get(question.getId());
                if (detail != null) {
                    qv.setOptions(detail.getOptions());
                    qv.setAnswer(detail.getAnswer());
                    qv.setAnalysis(detail.getAnalysis());
                }
                vo.setQuestion(qv);
            }
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 查询当前用户「已考完」的小节 id 集合
     * <p>只取 section_id 一列，避免把整条记录拉回来。</p>
     */
    @Override
    public List<Long> queryFinishedSectionIds() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Collections.emptyList();
        }
        return lambdaQuery()
                .select(ExamRecord::getSectionId)
                .eq(ExamRecord::getUserId, userId)
                .eq(ExamRecord::getStatus, STATUS_FINISHED)
                .list()
                .stream()
                .map(ExamRecord::getSectionId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 我的考试 · 全量统计
     * <p>
     * 列表改后端分页后，统计头必须基于全部记录算 —— 前端拿当前页凑数会随翻页变化。
     * 这里只 select 三列（score / finishTime / commitTime）走一次 list：
     * 一个人的考试记录量级是几百到几千，比写三条聚合 SQL（count / avg / max，
     * 再加一条「最高分那次是哪天」）更简单，也没有 N+1。
     * </p>
     */
    @Override
    public ExamStatsVO queryExamStats() {
        Long userId = UserContext.getUserId();
        ExamStatsVO vo = new ExamStatsVO();
        if (userId == null) {
            vo.setTotal(0L);
            return vo;
        }
        List<ExamRecord> rows = lambdaQuery()
                .select(ExamRecord::getScore, ExamRecord::getFinishTime, ExamRecord::getCreateTime)
                .eq(ExamRecord::getUserId, userId)
                .eq(ExamRecord::getStatus, STATUS_FINISHED)
                .orderByDesc(ExamRecord::getCreateTime)
                .list();
        vo.setTotal((long) rows.size());
        if (CollUtils.isEmpty(rows)) {
            return vo;
        }
        // 最近一次：列表已按创建时间倒序，头一条就是
        LocalDateTime last = timeOf(rows.get(0));
        vo.setLastWhen(formatDay(last));

        // 已判分的记录才参与均分 / 最高分（没交卷的 score 为 null）
        List<ExamRecord> scored = rows.stream()
                .filter(r -> r.getScore() != null)
                .collect(Collectors.toList());
        if (scored.isEmpty()) {
            return vo;
        }
        double sum = scored.stream().mapToDouble(r -> r.getScore()).sum();
        vo.setAvgScore(Math.round(sum / scored.size() * 10) / 10.0);

        ExamRecord best = scored.get(0);
        for (ExamRecord r : scored) {
            // 同分取更近的一次（列表倒序，先遇到的就是最近的）
            if (r.getScore() > best.getScore()) {
                best = r;
            }
        }
        vo.setMaxScore(best.getScore());
        vo.setMaxWhen(formatDay(timeOf(best)));
        return vo;
    }

    /** 交卷时间优先，没交卷时间就退回创建时间（PO 里没有 commitTime） */
    private LocalDateTime timeOf(ExamRecord r) {
        return r.getFinishTime() != null ? r.getFinishTime() : r.getCreateTime();
    }

    private String formatDay(LocalDateTime t) {
        return t == null ? "" : t.format(DAY_FORMAT);
    }
}
