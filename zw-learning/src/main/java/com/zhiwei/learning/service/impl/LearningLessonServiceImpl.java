package com.zhiwei.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiwei.api.client.course.CatalogueClient;
import com.zhiwei.api.client.course.CourseClient;
import com.zhiwei.api.dto.course.CataSimpleInfoDTO;
import com.zhiwei.api.dto.course.CourseFullInfoDTO;
import com.zhiwei.api.dto.course.CourseSimpleInfoDTO;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.domain.query.PageQuery;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.DateUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.learning.domian.dto.LearningPlanDTO;
import com.zhiwei.learning.domian.enums.LessonStatus;
import com.zhiwei.learning.domian.enums.PlanStatus;
import com.zhiwei.learning.domian.po.LearningLesson;
import com.zhiwei.learning.domian.po.LearningRecord;
import com.zhiwei.learning.domian.vo.LearningLessonVO;
import com.zhiwei.learning.domian.vo.LearningPlanPageVO;
import com.zhiwei.learning.domian.vo.LearningPlanVO;
import com.zhiwei.learning.domian.vo.LearningTodoVO;
import com.zhiwei.learning.mapper.LearningLessonMapper;
import com.zhiwei.learning.mapper.LearningRecordMapper;
import com.zhiwei.learning.service.ILearningLessonService;
import com.zhiwei.learning.service.IPointsRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 学生课程表 服务实现类
 * 提供学生课程相关的业务逻辑实现
 * </p>
 *
 * @author author
 * @since 2026-05-03
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson> implements ILearningLessonService {
    // 注入课程客户端，用于调用课程相关的API
    private final CourseClient courseClient;
    private final LearningRecordMapper learningRecordMapper;
    private final LearningLessonMapper learningLessonMapper;
    private final IPointsRecordService pointsRecordService;
    private final CatalogueClient catalogueClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;


    /**
     * 添加用户课程（幂等）
     *
     * @param userId    用户ID
     * @param courseIds 课程ID列表
     */
    @Override
    public void addUserLessons(Long userId, List<Long> courseIds) {

        // 1.调用课程客户端获取课程基本信息
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(courseIds);
        // 检查课程数据是否为空
        if (CollUtils.isEmpty(cInfoList)) {
            log.error("课程数据为空！无法添加到课表");
            return;
        }
        // 2.幂等过滤
        // learning_lesson 上有唯一索引 uk(user_id, course_id)：
        //   - 订单支付消息是 at-least-once 语义，重试/重投会让同一笔订单多次到达
        //   - 用户本身也可能重复下单买同一门课
        // 直接插入必然 Duplicate entry，且整批 saveBatch 失败会把同一订单里
        // 其他本该新增的课程一起回滚掉。所以先查一遍已在课表中的课程并剔除。
        List<Long> toAddCourseIds = cInfoList.stream()
                .map(CourseSimpleInfoDTO::getId)
                .collect(Collectors.toList());
        Set<Long> existCourseIds = lambdaQuery()
                .select(LearningLesson::getCourseId)
                .eq(LearningLesson::getUserId, userId)
                .in(LearningLesson::getCourseId, toAddCourseIds)
                .list()
                .stream()
                .map(LearningLesson::getCourseId)
                .collect(Collectors.toSet());
        // 创建学习课程列表，初始化大小为课程列表大小
        List<LearningLesson> list = new ArrayList<>(cInfoList.size());
        //3. 遍历课程信息列表
        for (CourseSimpleInfoDTO cInfo : cInfoList) {
            // 已在课表中，跳过，避免撞唯一索引
            if (existCourseIds.contains(cInfo.getId())) {
                log.info("课程已在课表中，跳过重复添加：userId={}, courseId={}", userId, cInfo.getId());
                continue;
            }
            // 创建学习课程对象
            LearningLesson lesson = new LearningLesson();
            // 获取课程有效期
            Integer validDuration = cInfo.getValidDuration();
            // 如果有效期大于0，设置创建时间和过期时间
            if (validDuration != null && validDuration > 0) {
                LocalDateTime now = LocalDateTime.now();
                lesson.setCreateTime(now);
                lesson.setExpireTime(now.plusMonths(validDuration));
            }
            // 设置用户ID和课程ID
            lesson.setUserId(userId);
            lesson.setCourseId(cInfo.getId());
            list.add(lesson);
        }
        // 全部已存在，无需写库
        if (list.isEmpty()) {
            return;
        }
        // 4.批量保存学习课程列表
        // 注：此处刻意不加 @Transactional —— 整个方法只有一个写操作，批量插入本身即原子；
        //    而下面需要捕获 DuplicateKeyException 降级，若方法带事务会被标记 rollback-only，
        //    捕获后提交仍会抛 UnexpectedRollbackException，消息照样消费失败。
        try {
            saveBatch(list);
        } catch (DuplicateKeyException e) {
            // 极端并发场景：同一用户同一课程的两条重复消息被不同消费者同时处理，
            // 上面的存在性查询双方都查不到，仍会撞唯一索引。
            // 降级为逐条插入，只丢掉真正重复的那条，不连累同批的其他课程。
            log.warn("批量插入撞唯一索引，降级为逐条插入：userId={}, courseIds={}", userId, toAddCourseIds);
            for (LearningLesson lesson : list) {
                try {
                    save(lesson);
                } catch (DuplicateKeyException ex) {
                    log.info("课程已在课表中，跳过：userId={}, courseId={}", userId, lesson.getCourseId());
                }
            }
        }
    }


    /**
     * 查询用户的学习课程列表
     *
     * @param pageQuery 分页查询参数
     * @return 包含学习课程信息的分页结果
     */
    @Override
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery pageQuery) {
        /// 获取当前用户ID
        Long userId = UserContext.getUserId();
        /// 根据用户ID分页查询学习记录，按最新学习时间降序排列
        Page<LearningLesson> page = lambdaQuery()
                .eq(LearningLesson::getUserId, userId).page(pageQuery.toMpPage("latest_learn_time", false));
        // 获取查询结果记录
        List<LearningLesson> records = page.getRecords();
        // 如果没有学习记录，返回空分页结果
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        Map<Long, CourseSimpleInfoDTO> map = getCourseSimpleInfoDTOMap(records);
        /// 创建结果列表
        List<LearningLessonVO> list = new ArrayList<>(records.size());
        /// 遍历学习记录，封装VO对象
        for (LearningLesson r : records) {
            // 复制学习记录属性到VO对象
            LearningLessonVO vo = BeanUtils.copyBean(r, LearningLessonVO.class);
            // 从Map中获取课程信息
            CourseSimpleInfoDTO cInfo = map.get(r.getCourseId());
            // 课程被删除/逻辑删除后，course-service 的 getSimpleInfoList 是 WHERE id IN (...)，直接不返回它，
            // map.get 就是 null。这里不判空会 NPE，把整个「我的课程」列表打成 500。
            // 同文件的 queryMyPlans 早就判了，列表这处一直漏着（2026-10-23 补）。
            if (cInfo != null) {
                // 设置课程相关信息到VO对象
                vo.setCourseName(cInfo.getName());
                vo.setCourseCoverUrl(cInfo.getCoverUrl());
                vo.setSections(cInfo.getSectionNum());
            }
            // 添加到结果列表
            list.add(vo);
        }
        /// 返回分页结果
        return PageDTO.of(page, list);
    }


    @Override
    /**
     * 获取用户当前正在学习的课程信息
     * @return LearningLessonVO 包含课程详细信息的视图对象
     */
    public LearningLessonVO getNowLesson() {
        //1.获取当前用户ID
        Long userId = UserContext.getUserId();
        // 2.查询用户最近学习的课表课程
        LearningLesson lesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)  // 设置查询条件：用户ID
                // 已过期（status=3）的课不算「正在学习」：过期课不该出现在 Header 下拉和 hero 卡里，
                // 更不该给一个能点进去的「继续学习」。原来不过滤，课程一过期只要它是最近学的就会被选中。
                // ⚠️ status 列允许 NULL（表默认 0，但历史数据可能是 NULL），必须把 NULL 一起放行 ——
                //    否则 `status <> 3` 会把 NULL 行也过滤掉（SQL 三值逻辑），静默丢一条课表记录。
                .and(w -> w.ne(LearningLesson::getStatus, LessonStatus.EXPIRED)
                        .or().isNull(LearningLesson::getStatus))
                .orderByDesc(LearningLesson::getLatestLearnTime)  // 按最新学习时间降序排序
                .last("limit 1")  // 只取最新的一条记录
                .one();
        // 如果没有找到学习记录，则返回null
        if (lesson == null) {
            return null;
        }
        // 创建VO对象，并复制属性
        LearningLessonVO vo = BeanUtils.copyBean(lesson, LearningLessonVO.class);
        // 4.通过课程客户端获取课程详细信息
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        // 5.如果课程信息不为空，则设置课程封面、名称和章节信息
        if (cInfo != null) {
            vo.setCourseCoverUrl(cInfo.getCoverUrl());  // 设置课程封面URL
            vo.setCourseName(cInfo.getName());  // 设置课程名称
            vo.setSections(cInfo.getSectionNum());  // 设置课程章节数量
        }
        // 6.统计课表中「还能学」的课程数
        //    原来是无条件 count(*)，已失效（status=3）的课也占一个名额：
        //    用户在 Header 看到「5 门课程」，点进「全部课程」只有 4 门能学，数字对不上。
        //    这里统一口径 = 可学课程数（与上面 getNowLesson 挑「正在学习」用的是同一套过滤）。
        //    ⚠️ status 列允许 NULL，`status <> 3` 会把 NULL 行一起过滤掉（SQL 三值逻辑），
        //       必须显式 or().isNull() 放行，否则会凭空少算课程。
        Long count = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)  // 设置查询条件：用户ID
                .and(w -> w.ne(LearningLesson::getStatus, LessonStatus.EXPIRED)
                        .or().isNull(LearningLesson::getStatus))
                .count();  // 执行计数查询

        vo.setCourseAmount(count.intValue());
        // 7.查询「最近学的那一小节」的名称和序号
        //    ⚠️ batchQueryCatalogue 的参数是【目录 id】，不是 courseId。
        //    原来传的是 lesson.getCourseId()，服务端执行 WHERE id IN (courseId)，
        //    撞上别的课程的目录 id 就返回那条（几门课目录同名，看着像对的，其实串课了），
        //    撞不上就返回空、这行直接消失。而且取的是 list.get(0)，跟「学到哪一节」毫无关系，
        //    所以永远显示目录第一条 = 第 1 节（2026-10-23 实测：latest_section_id=38 却显示第 1 节）。
        //    latestSectionId 为 null（从没学过）或指向的小节已被删（大纲改过）时不设置，
        //    前端 v-if 会把这一行藏掉 —— 宁可不说，也不编一个假的「第 1 节」。
        if (lesson.getLatestSectionId() != null) {
            List<CataSimpleInfoDTO> latestSection = catalogueClient.batchQueryCatalogue(
                    CollUtils.singletonList(lesson.getLatestSectionId()));
            if (!CollUtils.isEmpty(latestSection)) {
                vo.setLatestSectionName(latestSection.get(0).getName());
                vo.setLatestSectionIndex(latestSection.get(0).getCIndex());
            }
        }
        // 返回包含课程详细信息的视图对象
        return vo;
    }

    private Map<Long, CourseSimpleInfoDTO> getCourseSimpleInfoDTOMap(List<LearningLesson> records) {
        // 提取所有课程ID
        Set<Long> cIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        /// 根据课程ID列表获取课程基本信息
        List<CourseSimpleInfoDTO> courseInfoList = courseClient.getSimpleInfoList(cIds);
        // 课程信息为空时返回空 Map，绝不能抛异常。
        // 两种场景都会走到这里，都不该让整个接口 500：
        //   ① 用户学过的课全被逻辑删除 —— course-service 的 getSimpleInfoList 是 WHERE id IN (...)，
        //      已删除的课直接不返回，结果就是空列表。这是正常业务状态，应该返回「暂无课程」。
        //      原来这里 throw BadRequestException，把「课程全删」这种正常情况也打成了 500。
        //   ② course-service 不可用 —— CourseClientFallback 降级返回空列表。
        // 下游 queryMyLessons(约 175 行) / queryMyPlans(约 486 行) 都有 if (cInfo != null) 判空，
        // 拿到空 Map 只会让课程名/封面为空，学习进度和列表本身照常返回。
        if (CollUtils.isEmpty(courseInfoList)) {
            return new HashMap<>();
        }
        // 将课程信息列表转换为Map，以课程ID为键
        Map<Long, CourseSimpleInfoDTO> map = courseInfoList
                .stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, cInfo -> cInfo));
        return map;
    }


    /**
     * 删除已过期的课程
     *
     * @param courseId 课程ID，需要删除的课程标识
     * @throws BadRequestException 当课程ID为空、用户ID为空或删除失败时抛出异常
     */
    @Override
    public void deleteMyLesson(Long courseId) {
        Long userId = UserContext.getUserId();
        if (courseId == null) {
            throw new BadRequestException("课程ID不能为空！");
        }
        if (userId == null) {
            throw new BadRequestException("用户ID不能为空！");
        }
        LocalDateTime now = LocalDateTime.now();
        // 创建Lambda更新包装器，设置删除条件：用户ID、课程ID匹配且过期时间小于当前时间
        LambdaQueryWrapper<LearningLesson> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LearningLesson::getUserId, userId).eq(LearningLesson::getCourseId, courseId).lt(LearningLesson::getExpireTime, now);
        boolean removed = remove(wrapper);
        if (!removed) {
            throw new BadRequestException("删除失败！仅允许删除已过期的课程");
        }
        log.info("用户{}删除过期课程成功，课程id：{}", userId, courseId);
    }


    /**
     * 批量删除已退款的课程（MQ异步通知）
     *
     * @param userId    用户ID
     * @param courseIds 课程ID列表
     * @throws BadRequestException 当用户ID为空或课程ID列表为空时抛出异常
     */
    @Override
    public void deleteMyLessons(Long userId, List<Long> courseIds) {
        if (userId == null) {
            throw new BadRequestException("用户ID不能为空！");
        }
        if (CollUtils.isEmpty(courseIds)) {
            throw new BadRequestException("课程ID列表不能为空！");
        }
        // 创建Lambda查询条件，设置用户ID和课程ID
        LambdaQueryWrapper<LearningLesson> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LearningLesson::getUserId, userId).in(LearningLesson::getCourseId, courseIds);
        boolean removed = remove(wrapper);
        if (!removed) {
            log.warn("退款取消课程时未找到记录，可能是重复消息，userId：{}，courseIds：{}", userId, courseIds);
        } else {
            log.info("退款取消课程成功，userId：{}，courseIds：{}", userId, courseIds);
        }
    }


    /**
     * 验证课程是否有效的接口方法
     *
     * @param courseId 课程ID
     * @return 课程ID（如果课程有效且未过期）或null（如果课程已过期）
     * @throws BadRequestException 当课程ID为空或课程不存在时抛出异常
     */
    @Override
    public Long isLessonValid(Long courseId) {
        Long userId = UserContext.getUserId();
        if (courseId == null) {
            throw new BadRequestException("课程ID不能为空！");
        }
        LearningLesson lesson = lambdaQuery()
                .select(LearningLesson::getId, LearningLesson::getExpireTime)
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            // 同 getLessonInfo：查 learning_lesson 查不到 = 没买，不是「课程不存在」，也不该抛异常。
            // 调用方 media 服务用的是 `lessonId != null` 判断是否购买，而它的 fallback 也返回 null，
            // 所以这里改成 return null 行为完全一致，还省掉一次 Feign 异常 + fallback 的 error 日志。
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        // 检查课程是否过期：expire_time 为 null 代表「永久有效」，必须放行。
        // 原逻辑是 `expireTime != null && expireTime.isAfter(now)` —— 把 null 判成了无效，
        // 于是永久课程（表里 expire_time 为 NULL 的那些）调这个接口恒返回 null，
        // 调用方会把它当成「已过期」拦掉，买过的课打不开。
        if (lesson.getExpireTime() == null || lesson.getExpireTime().isAfter(now)) {
            return lesson.getId();
        }
        return null;
    }

    /**
     * 获取课程信息的方法
     *
     * @param courseId 课程ID
     * @return LearningLessonVO 课程信息视图对象；**用户没买这门课时返回 null**（不是异常）
     * @throws BadRequestException 当课程ID为空时抛出异常
     */
    @Override
    public LearningLessonVO getLessonInfo(Long courseId) {
        Long userId = UserContext.getUserId();
        if (courseId == null) {
            throw new BadRequestException("课程ID不能为空！");
        }
        LearningLesson lesson = lambdaQuery()
                .select(LearningLesson::getId, LearningLesson::getCourseId, LearningLesson::getStatus,
                        LearningLesson::getExpireTime, LearningLesson::getCreateTime, LearningLesson::getLearnedSections,
                        LearningLesson::getPlanStatus)
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            // 这里查的是 learning_lesson（用户课程表），查不到只代表「这个用户没买这门课」，
            // 不代表课程不存在 —— 课程本身的信息归 course 服务管。
            // 返回 null 而不是抛 BadRequestException：调用方（课程详情页）本来就是靠 data 为空
            // 来判断「未购买」、切换「加入购物车 / 马上学习」两个按钮。抛异常只会让每个未购用户
            // 每次打开详情页都在日志里留一条 400，排查时是纯噪音。
            return null;
        }
        // 判空放在 copyBean 之前：copyBean(null) 虽然返回 null 不会 NPE，
        // 但读起来像「先用了再判」，以后谁把 copyBean 换成别的转换器就会炸。
        return BeanUtils.copyBean(lesson, LearningLessonVO.class);
    }

    /**
     * 根据课程ID统计学习课程的数量
     *
     * @param courseId 课程ID
     * @return 返回该课程的学习数量，如果courseId为null则返回0
     */
    @Override
    public Long countLearningLessonByCourse(Long courseId) {
        if (courseId == null) {
            return 0L;
        }
        Long count = lambdaQuery().eq(LearningLesson::getCourseId, courseId).count();
        if (count == null) {
            return 0L;
        }
        return count;
    }

    @Override
    public LearningLesson queryByUserIdAndCourseId(Long userId, Long courseId) {
        LearningLesson lesson = lambdaQuery().eq(LearningLesson::getUserId, userId).eq(LearningLesson::getCourseId, courseId).one();
        return lesson;

    }


    @Override
    public void createLearningPlans(LearningPlanDTO learningPlanDTO) {
        Long userId = UserContext.getUserId();
        LearningLesson lesson = queryByUserIdAndCourseId(userId, learningPlanDTO.getCourseId());
        if (lesson == null) {
            throw new BadRequestException("课程不存在,创建学习计划失败！");
        }
        LearningLesson l = new LearningLesson();
        l.setId(lesson.getId());
        l.setWeekFreq(learningPlanDTO.getFreq());
        if (lesson.getPlanStatus() == PlanStatus.NO_PLAN) {
            l.setPlanStatus(PlanStatus.PLAN_RUNNING);
        }
        updateById(l);
    }

    @Override
/**
 * 查询用户的学习计划分页信息
 * @param pageQuery 分页查询参数
 * @return LearningPlanPageVO 学习计划分页视图对象，包含本周已完成和总计划数
 */
    public LearningPlanPageVO queryMyPlans(PageQuery pageQuery) {
        Long userId = UserContext.getUserId();
        // 创建学习计划分页视图对象
        LearningPlanPageVO result = new LearningPlanPageVO();
        LocalDate now = LocalDate.now();
        LocalDateTime beginTime = DateUtils.getWeekBeginTime(now);
        LocalDateTime endTime = DateUtils.getWeekEndTime(now);
        /// 查询用户本周已完成的学习记录数量
        Long count = learningRecordMapper.selectCount(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)  // 设置用户ID条件
                .eq(LearningRecord::getFinished, true)  // 设置已完成条件
                .between(LearningRecord::getFinishTime, beginTime, endTime));  // 设置本周完成时间范围
        // 设置本周已完成数量
        result.setWeekFinished(count.intValue());
        /// 查询用户总计划数
        Long queryTotalPlan = learningLessonMapper.queryTotalPlan(userId);
        // 设置总计划数

        result.setWeekTotalPlan(queryTotalPlan != null ? queryTotalPlan.intValue() : 0);
        // 本周积分：points_record 里本周所有类型的积分之和（签到/学习/问答/笔记/评价都算）。
        // begin/end 与上面 weekFinished 用同一对周界，避免两个数字按不同周期算而对不上。
        // 跨月那周由 queryWeekPoints 内部补上月分表，这里不用关心。
        result.setWeekPoints(pointsRecordService.queryWeekPoints(userId, beginTime, endTime));
        Page<LearningLesson> page = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .in(LearningLesson::getStatus, LessonStatus.LEARNING, LessonStatus.NOT_BEGIN)
                .eq(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .page(pageQuery.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return result.pageInfo(page.getTotal(), page.getPages(), CollUtils.emptyList());
        }

        Map<Long, CourseSimpleInfoDTO> cInfoMap = getCourseSimpleInfoDTOMap(records);
        List<LearningRecord> learningRecords = learningRecordMapper.selectList(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getFinished, true)
                .between(LearningRecord::getFinishTime, beginTime, endTime));

        Map<Long, Long> recordsMap = learningRecords
                .stream()
                .collect(Collectors.groupingBy(LearningRecord::getLessonId, Collectors.counting()));

        List<LearningPlanVO> voList = BeanUtils.copyList(records, LearningPlanVO.class);
        for (LearningPlanVO vo : voList) {
            CourseSimpleInfoDTO cInfo = cInfoMap.get(vo.getCourseId());
            if (cInfo != null) {
                vo.setCourseName(cInfo.getName());
                vo.setSections(cInfo.getSectionNum());
            }
            vo.setWeekLearnedSections(recordsMap.getOrDefault(vo.getId(), 0L).intValue());
        }
        return result.pageInfo(page.getTotal(), page.getPages(), voList);
    }


    @Override
    public void updateExpiredLessonsStatus() {
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<LearningLesson>()
                .set(LearningLesson::getStatus, LessonStatus.EXPIRED)
                .in(LearningLesson::getStatus, LessonStatus.LEARNING, LessonStatus.NOT_BEGIN, LessonStatus.FINISHED)
                .lt(LearningLesson::getExpireTime, LocalDateTime.now()));
        log.info("定时任务执行了：更新了{}个已过期课程状态", updated);

    }

    /**
     * 学习概览页的待办 + 即将到期。
     * <p>
     * 口径约定：
     * <ul>
     *   <li>planGap 复用 {@link #queryMyPlans} 的周口径（同一对周界 + 同一个 queryTotalPlan），
     *       不另写一套统计 —— 否则概览页和「我的课程」页的数字会打架；</li>
     *   <li>expiring 只取 status IN (0, 1)（未学习/学习中）—— 学完的课提醒续期是噪音，
     *       还会占掉待办位置；</li>
     *   <li>daysLeft 按自然日算（0 = 今天到期），前端把 0 显示成「今天到期」；</li>
     *   <li>课程名走 CourseClient 批量取，不循环单查。</li>
     * </ul>
     * 整体 try/catch：任何异常都返回空结构 —— 这是装饰性接口，不能把概览页打挂。
     * 缓存 key 带「当天」，跨天自动失效；Redis 不可用时降级为直接查库。
     */
    @Override
    public LearningTodoVO learningTodo() {
        LearningTodoVO vo = new LearningTodoVO();
        vo.setPlanGap(new LearningTodoVO.PlanGap());
        vo.getPlanGap().setWeekFinished(0);
        vo.getPlanGap().setWeekTotalPlan(0);
        vo.getPlanGap().setGap(0);
        vo.setExpiring(CollUtils.emptyList());
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return vo;
        }

        String cacheKey = "ls:todo:" + userId + ":" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                return objectMapper.readValue(json, LearningTodoVO.class);
            }
        } catch (Exception ignore) {
        }

        try {
            LocalDate now = LocalDate.now();
            LocalDateTime beginTime = DateUtils.getWeekBeginTime(now);
            LocalDateTime endTime = DateUtils.getWeekEndTime(now);
            // 本周完成数：与 queryMyPlans 完全同一对周界、同一个 finished 口径
            Long weekFinished = learningRecordMapper.selectCount(new LambdaQueryWrapper<LearningRecord>()
                    .eq(LearningRecord::getUserId, userId)
                    .eq(LearningRecord::getFinished, true)
                    .between(LearningRecord::getFinishTime, beginTime, endTime));
            int finished = weekFinished == null ? 0 : weekFinished.intValue();
            Long totalPlan = learningLessonMapper.queryTotalPlan(userId);
            int plan = totalPlan == null ? 0 : totalPlan.intValue();
            vo.getPlanGap().setWeekFinished(finished);
            vo.getPlanGap().setWeekTotalPlan(plan);
            vo.getPlanGap().setGap(Math.max(0, plan - finished));

            // 7 天内到期且未学完的课
            List<LearningLesson> lessons = lambdaQuery()
                    .eq(LearningLesson::getUserId, userId)
                    .in(LearningLesson::getStatus, LessonStatus.NOT_BEGIN, LessonStatus.LEARNING)
                    .isNotNull(LearningLesson::getExpireTime)
                    .between(LearningLesson::getExpireTime, LocalDateTime.now(), LocalDateTime.now().plusDays(7))
                    .orderByAsc(LearningLesson::getExpireTime)
                    .last("LIMIT 5")
                    .list();
            List<LearningTodoVO.ExpiringCourse> expiring = new ArrayList<>();
            if (CollUtils.isNotEmpty(lessons)) {
                List<Long> cIds = lessons.stream().map(LearningLesson::getCourseId).collect(Collectors.toList());
                Map<Long, CourseSimpleInfoDTO> cMap = new HashMap<>();
                List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(cIds);
                if (CollUtils.isNotEmpty(cInfoList)) {
                    cMap = cInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
                }
                for (LearningLesson l : lessons) {
                    LearningTodoVO.ExpiringCourse e = new LearningTodoVO.ExpiringCourse();
                    e.setCourseId(l.getCourseId());
                    CourseSimpleInfoDTO info = cMap.get(l.getCourseId());
                    // 课程可能已被删除/逻辑删除：getSimpleInfoList 不返回它，名称兜底为空串，前端跳详情页再兜底
                    e.setCourseName(info != null ? info.getName() : "");
                    e.setExpireTime(l.getExpireTime());
                    e.setDaysLeft(Math.max(0, (int) ChronoUnit.DAYS.between(LocalDate.now(), l.getExpireTime().toLocalDate())));
                    expiring.add(e);
                }
            }
            vo.setExpiring(expiring);

            try {
                redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(vo), Duration.ofMinutes(5));
            } catch (Exception ignore) {
            }
            return vo;
        } catch (Exception e) {
            log.warn("学习待办聚合失败 userId={}", userId, e);
            return vo;
        }
    }
}
