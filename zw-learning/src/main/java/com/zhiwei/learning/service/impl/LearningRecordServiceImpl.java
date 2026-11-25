package com.zhiwei.learning.service.impl;

import com.zhiwei.api.client.course.CourseClient;
import com.zhiwei.api.dto.course.CourseFullInfoDTO;
import com.zhiwei.api.dto.leanring.LearningLessonDTO;
import com.zhiwei.api.dto.leanring.LearningRecordDTO;
import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.exceptions.DbException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.learning.Utils.LearningRecordDelayTaskHandler;
import com.zhiwei.learning.domian.dto.LearningRecordFormDTO;
import com.zhiwei.learning.domian.enums.LessonStatus;
import com.zhiwei.learning.domian.enums.SectionType;
import com.zhiwei.learning.domian.po.LearningLesson;
import com.zhiwei.learning.domian.po.LearningRecord;
import com.zhiwei.learning.domian.vo.LearningDailyStatVO;
import com.zhiwei.learning.domian.vo.LearningSummaryVO;
import com.zhiwei.learning.mapper.LearningRecordMapper;
import com.zhiwei.learning.service.ILearningLessonService;
import com.zhiwei.learning.service.ILearningRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {
    private final ILearningLessonService lessonService;
    private final CourseClient courseClient;
    private final LearningRecordDelayTaskHandler taskHandler;
    private final RabbitMqHelper mqHelper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 查询用户在指定课程下的学习进度
     * <p>
     * 流程：
     * <ol>
     *   <li>根据当前登录用户和课程ID查询课表，未报名直接返回 null</li>
     *   <li>根据课表ID查询所有小节的学习记录，组装课表ID + 各小节播放进度返回</li>
     * </ol>
     *
     * @param courseId 课程ID
     * @return 课表ID及该课表下所有小节的学习记录，未报名返回 null
     */
    @Override
    public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUserId();
        // 2. 查询用户在该课程下的课表
        LearningLesson lesson = lessonService.queryByUserIdAndCourseId(userId, courseId);
        // 2.1 未报名该课程，返回 null
        if (lesson == null) {
            return null;
        }
        // 3. 根据课表ID查询所有小节的学习记录，按最近学习时间倒序
        //    排序不只是为了好看：调用方（course-service）在 latestSectionId 为空时会退化成取第一条记录，
        //    原来没有 order by，MySQL 实际按主键顺序返回（恒为 id 最小那条），
        //    表现就是「每次打开学习页都回到第 1 节」，和「最近学习」完全无关。
        List<LearningRecord> list = lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId())
                .orderByDesc(LearningRecord::getUpdateTime)
                .list();
        // 4. 组装返回：课表ID + 最近学习的小节 + 各小节播放进度
        //    latestSectionId 必须显式带出去：learning_lesson 里一直在维护这个字段（延迟任务落库时更新），
        //    不传的话调用方只能拿 records 的第一条兜底，落点就会和真实的「上次学到哪」对不上。
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        dto.setRecords(BeanUtils.copyList(list, LearningRecordDTO.class));
        return dto;
    }

    /**
     * 提交学习记录（视频播放进度 / 考试记录）
     * <p>
     * 流程：
     * <ol>
     *   <li>根据小节类型分发：考试直接入库，视频走延迟队列异步写</li>
     *   <li>本次完成学习时，推送积分消息（非关键路径，异常吞掉）</li>
     *   <li>更新课表状态：已学习小节数 +1，判断是否全部完成</li>
     * </ol>
     * 视频进度高频写优化：非首次且未完成时，走 Redis Hash 合并写 + Redisson 延迟队列 20 秒异步回写，
     * 而非直接写 DB，将写库频率降低约 60 倍。
     *
     * @param recordDTO 学习记录请求体，包含 lessonId、sectionId、sectionType、moment、duration 等
     */
    @Override
    public void addLearningRecord(LearningRecordFormDTO recordDTO) {
        // 1. 获取当前登录用户
        Long userId = UserContext.getUserId();
        boolean needUpdateLesson;
        // 2. 根据小节类型分发处理：考试 or 视频
        if (recordDTO.getSectionType() == SectionType.EXAM) {
            // 考试记录直接入库
            needUpdateLesson = handleExamRecord(userId, recordDTO);
        } else {
            // 视频记录走延迟队列异步写库
            needUpdateLesson = handleVideoRecord(userId, recordDTO);
        }
        // 3. 未完成学习，无需后续处理（视频进度由延迟队列异步写库，不在此处触发）
        if (!needUpdateLesson) {
            return;
        }
        // 4. 本次完成了学习，推送积分消息（非关键路径，异常吞掉不影响主流程）
        try {
            mqHelper.send(
                    MqConstants.Exchange.LEARNING_EXCHANGE,
                    MqConstants.Key.LEARN_SECTION,
                    userId);
        } catch (Exception e) {
            log.error("推送学习积分消息异常:{}", e);
        }
        // 5. 更新课表状态：已学习小节数 +1，判断是否全部完成
        handleLearningLessonCourse(recordDTO);
    }

    /**
     * 更新课表状态：已学习小节数 +1，同时判断课程是否全部完成
     * <p>
     * 流程：
     * <ol>
     *   <li>查询课表信息，校验课程是否存在</li>
     *   <li>通过 Feign 远程调用获取课程总小节数</li>
     *   <li>原子更新：learned_sections +1，根据条件设置状态为 LEARNING / FINISHED</li>
     * </ol>
     *
     * @param recordDTO 学习记录请求体
     */
    private void handleLearningLessonCourse(LearningRecordFormDTO recordDTO) {
        // 1. 查询课表信息
        LearningLesson lesson = lessonService.getById(recordDTO.getLessonId());
        if (lesson == null) {
            throw new BizIllegalException("课程不存在,无法更新数据");
        }
        // 2. 远程调用获取课程总小节数
        CourseFullInfoDTO course = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (course == null) {
            throw new BizIllegalException("课程不存在,无法更新数据");
        }
        // 3. 判断本次完成后是否所有小节都已完成
        boolean allLearned = lesson.getLearnedSections() + 1 >= course.getSectionNum();

        // 4. 原子更新：learned_sections +1，同时根据条件更新学习状态
        lessonService.lambdaUpdate()
                .eq(LearningLesson::getId, lesson.getId())
                .set(lesson.getLearnedSections() == 0, LearningLesson::getStatus, LessonStatus.LEARNING)
                .set(allLearned, LearningLesson::getStatus, LessonStatus.FINISHED)
                .setSql("learned_sections = learned_sections + 1")
                .update();
    }

    /**
     * 处理视频播放进度上报
     * <p>
     * 三条路径：
     * <ol>
     *   <li><b>首次观看</b>：直接插入学习记录，返回 false（不触发课表更新）</li>
     *   <li><b>非首次且未完成</b>：走 Redis Hash 合并写 + Redisson 延迟队列 20 秒异步回写，返回 false</li>
     *   <li><b>本次完成了学习</b>（moment × 2 ≥ duration）：直接更新 DB 并清理缓存，返回 true</li>
     * </ol>
     * 完成判定：之前未标记完成 且 本次观看进度超过视频总时长的一半。
     *
     * @param userId    当前登录用户ID
     * @param recordDTO 学习记录请求体
     * @return true-本次完成了学习，需要更新课表；false-未完成，无需后续处理
     */
    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO recordDTO) {
        // 1. 先查缓存再查DB，获取历史学习记录
        LearningRecord old = queryOldRecord(recordDTO.getLessonId(), recordDTO.getSectionId());
        // 路径1：首次观看该小节，直接插入学习记录
        if (old == null) {
            LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
            record.setUserId(userId);
            boolean success = save(record);
            if (!success) {
                throw new DbException("保存学习记录失败");
            }
            return false;
        }
        // 2. 兼容历史数据：finished 可能为 null，统一转为 false
        if (old.getFinished() == null) {
            old.setFinished(false);
        }
        // 3. 判断是否首次完成：之前未完成 且 本次观看进度超过视频总时长的一半
        boolean isFirstTimeFinished = (!old.getFinished())
                && recordDTO.getDuration() != null
                && (recordDTO.getMoment() * 2) >= recordDTO.getDuration();
        // 路径2：非首次且未完成，走 Redis Hash 合并写 + 延迟队列 20 秒异步回写
        if (!isFirstTimeFinished) {

            LearningRecord record = new LearningRecord();
            record.setId(old.getId());
            record.setLessonId(recordDTO.getLessonId());
            record.setSectionId(recordDTO.getSectionId());
            record.setMoment(recordDTO.getMoment());
            record.setFinished(old.getFinished());
            taskHandler.addLearningRecordTask(record);
            return false;
        }
        // 路径3：本次完成了学习，直接更新 DB 并清理 Redis 缓存
        boolean success = lambdaUpdate()
                .set(LearningRecord::getMoment, recordDTO.getMoment())
                .set(LearningRecord::getFinished, true)
                .set(LearningRecord::getFinishTime, recordDTO.getCommitTime())
                .eq(LearningRecord::getLessonId, recordDTO.getLessonId())
                .eq(LearningRecord::getSectionId, recordDTO.getSectionId())
                .update();
        if (!success) {
            throw new DbException("更新学习记录失败");
        }
        // 4. 清理 Redis 缓存，避免缓存中 finished 仍为 false 导致下次重复触发完成逻辑
        taskHandler.clearRecordCache(recordDTO.getLessonId(), recordDTO.getSectionId());
        return true;
    }

    /**
     * 查询历史学习记录，缓存优先
     * <p>
     * 流程：
     * <ol>
     *   <li>先查 Redis 缓存（延迟任务写入的合并缓存），命中直接返回</li>
     *   <li>缓存未命中则查 DB，存在则回写缓存加速后续查询</li>
     * </ol>
     *
     * @param lessonId  课表ID
     * @param sectionId 小节ID
     * @return 历史学习记录，不存在返回 null
     */
    private LearningRecord queryOldRecord(Long lessonId, Long sectionId) {
        // 1. 先查 Redis 缓存（延迟任务写入的合并缓存，可能包含最新的 moment）
        LearningRecord record = taskHandler.readRecordCache(lessonId, sectionId);
        if (record != null) {
            return record;
        }
        // 2. 缓存未命中，查 DB
        record = lambdaQuery()
                .eq(LearningRecord::getLessonId, lessonId)
                .eq(LearningRecord::getSectionId, sectionId)
                .one();
        // 3. DB 有数据则回写缓存，加速后续查询
        if (record != null) {
            taskHandler.writeRecordCache(record);
        }
        return record;
    }

    /**
     * 处理考试记录提交
     * <p>
     * 考试提交即视为完成学习，直接标记 finished=true 并入库。
     *
     * @param userId    当前登录用户ID
     * @param recordDTO 学习记录请求体
     * @return true-需要更新课表
     */
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO recordDTO) {
        // 1. 构建学习记录，考试提交即标记完成
        LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
        record.setUserId(userId);
        record.setFinished(true);
        record.setFinishTime(recordDTO.getCommitTime());
        // 2. 插入考试记录
        boolean success = save(record);
        if (!success) {
            throw new DbException("保存考试记录失败");
        }
        return true;
    }

    /**
     * 学习活跃度按天聚合（概览页本周节奏、报告页热力图/指标卡）。
     * <p>
     * 口径约定：
     * <ul>
     *   <li>sections = 当日 DISTINCT section_id —— 同一天反复看同一小节只算一次；</li>
     *   <li>totalSections = 区间内 DISTINCT section_id —— 跨天重复学习的同一小节也只算一次，
     *       所以不能用「每日 sections 求和」代替（那会把跨天重复算成两次）；</li>
     *   <li>activeDays = daily 里 sections &gt; 0 的天数（补 0 之后的 daily 长度恒等于 days，
     *       不能直接拿长度当活跃天数）；</li>
     *   <li>moment 是播放位置不是累计时长，这里不做任何时长统计。</li>
     * </ul>
     * 缓存 key 带「当天」，跨天自动失效；Redis 不可用时降级为直接查库。
     */
    @Override
    public LearningSummaryVO learningSummary(Integer days) {
        Long userId = UserContext.getUserId();
        LearningSummaryVO vo = new LearningSummaryVO();
        int d = (days == null || days <= 0) ? 30 : Math.min(days, 90);
        vo.setDays(d);
        vo.setTotalSections(0L);
        vo.setActiveDays(0);
        vo.setDaily(CollUtils.emptyList());
        if (userId == null) {
            return vo;
        }

        String todayKey = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String cacheKey = "ls:summary:" + userId + ":" + d + ":" + todayKey;
        // 读缓存：命中直接返回。反序列化失败按未命中处理（缓存脏数据不致命）
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                return objectMapper.readValue(json, LearningSummaryVO.class);
            }
        } catch (Exception ignore) {
        }

        try {
            LocalDateTime begin = LocalDate.now().minusDays(d - 1L).atStartOfDay();
            Map<LocalDate, LearningDailyStatVO> statMap = baseMapper.selectDailyStats(userId, begin)
                    .stream()
                    .collect(Collectors.toMap(LearningDailyStatVO::getStatDate, s -> s));

            // 缺失日期补 0：柱状图缺柱、热力图错位都是补全职责漏掉的症状，前端不补
            List<LearningDailyStatVO> daily = new ArrayList<>(d);
            LocalDate cur = LocalDate.now().minusDays(d - 1L);
            for (int i = 0; i < d; i++) {
                LearningDailyStatVO row = statMap.get(cur);
                if (row == null) {
                    row = new LearningDailyStatVO();
                    row.setStatDate(cur);
                    row.setSections(0L);
                    row.setFinished(0L);
                }
                daily.add(row);
                cur = cur.plusDays(1);
            }
            vo.setDaily(daily);
            Long total = baseMapper.selectTotalSections(userId, begin);
            vo.setTotalSections(total == null ? 0L : total);
            vo.setActiveDays((int) daily.stream()
                    .filter(s -> s.getSections() != null && s.getSections() > 0)
                    .count());

            try {
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(vo), Duration.ofMinutes(5));
            } catch (Exception ignore) {
            }
            return vo;
        } catch (Exception e) {
            // 统计是装饰性数据：查库失败返回空结构（0 值 + 空 daily），不把概览页打挂
            log.warn("学习活跃度聚合失败 userId={} days={}", userId, d, e);
            return vo;
        }
    }
}