package com.zhiwei.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiwei.api.cache.CategoryCache;
import com.zhiwei.api.client.course.CatalogueClient;

import com.zhiwei.api.client.course.CourseClient;
import com.zhiwei.api.client.search.SearchClient;
import com.zhiwei.api.client.user.UserClient;
import com.zhiwei.api.dto.course.CataSimpleInfoDTO;
import com.zhiwei.api.dto.course.CatalogueDTO;
import com.zhiwei.api.dto.course.CourseFullInfoDTO;
import com.zhiwei.api.dto.course.CourseSimpleInfoDTO;
import com.zhiwei.api.dto.user.UserDTO;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.learning.domian.dto.QuestionFormDTO;
import com.zhiwei.learning.domian.enums.QuestionStatus;
import com.zhiwei.learning.domian.po.InteractionQuestion;
import com.zhiwei.learning.domian.po.InteractionReply;
import com.zhiwei.learning.domian.query.QuestionAdminPageQuery;
import com.zhiwei.learning.domian.query.QuestionPageQuery;
import com.zhiwei.learning.domian.vo.QuestionAdminVO;
import com.zhiwei.learning.domian.vo.QuestionVO;
import com.zhiwei.learning.mapper.InteractionQuestionMapper;
import com.zhiwei.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiwei.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-05-10
 */
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {
    private final UserClient userClient;
    private final IInteractionReplyService replyService;
    private final CourseClient courseClient;
    private final SearchClient searchClient;
    private final CategoryCache categoryCache;
    private final CatalogueClient catalogueClient;

    @Override
    public void addQuestion(QuestionFormDTO questionFormDTO) {
        Long userId = UserContext.getUserId();
        InteractionQuestion question = BeanUtils.copyBean(questionFormDTO, InteractionQuestion.class);
        question.setUserId(userId);
        save(question);
    }


    @Override
    public void updateQuestion(QuestionFormDTO questionFormDTO, Long id) {
        if (id == null) {
            return;
        }
        InteractionQuestion question = getById(id);
        if (question == null) {
            throw new BadRequestException("问题不存在");
        }
        question.setTitle(questionFormDTO.getTitle());
        question.setDescription(questionFormDTO.getDescription());
        question.setAnonymity(questionFormDTO.getAnonymity());
        updateById(question);
    }


    @Override
    @Transactional
    public void removeMyQuestion(Long id) {
        Long userId = UserContext.getUserId();
        if (id == null) {
            return;
        }
        InteractionQuestion question = getById(id);
        if (question == null) {
            throw new BadRequestException("问题不存在");
        }
        if (!Objects.equals(question.getUserId(), userId)) {
            throw new BadRequestException("只能删除自己的问题");
        }
        //删除问题的回答
        replyService.removeByQuestionId(id);
        //删除问题
        removeById(id);
    }


    @Override
    public void updateQuestionHidden(Long id, Boolean hidden) {
        if (id == null || hidden == null) {
            return;
        }
        // 校验问题是否存在
        boolean exists = lambdaQuery().eq(InteractionQuestion::getId, id).exists();
        if (!exists) {
            throw new BadRequestException("问题不存在");
        }
        lambdaUpdate().set(InteractionQuestion::getHidden, hidden)
                .eq(InteractionQuestion::getId, id)
                .update();
    }

    /**
     * 查询问题分页列表
     *
     * @param query 查询条件，包含课程ID、章节ID、是否只查询当前用户的问题等
     * @return 返回包含问题列表的分页结果
     */
    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        Long courseId = query.getCourseId();
        Long sectionId = query.getSectionId();
        // 校验课程ID和章节ID不能同时为空
        if (courseId == null && sectionId == null) {
            throw new BadRequestException("课程id和章节id不能同时为空");
        }
        // 判断是否只查询当前用户的问题
        boolean onlyMine = Boolean.TRUE.equals(query.getOnlyMine());
        // 查询问题列表，不查询description字段
        Page<InteractionQuestion> page = lambdaQuery()
                .select(InteractionQuestion.class, info -> !info.getProperty().equals("description"))  //不查询description字段
                .eq(courseId != null, InteractionQuestion::getCourseId, courseId)
                .eq(sectionId != null, InteractionQuestion::getSectionId, sectionId)
                // 根据用户ID筛选（仅当onlyMine为true时生效）
                .eq(onlyMine, InteractionQuestion::getUserId, UserContext.getUserId())
                // 只查询未隐藏的问题
                .eq(InteractionQuestion::getHidden, false)
                // 设置分页参数，按创建时间降序排列
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        // 获取查询结果记录
        List<InteractionQuestion> records = page.getRecords();
        // 如果结果为空，返回空分页
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 收集用户ID（提问）和回答ID
        Set<Long> userIds = new HashSet<>();
        Set<Long> answerIds = new HashSet<>();
        for (InteractionQuestion q : records) {
            // 如果问题不是匿名，添加用户ID
            if (!q.getAnonymity()) {
                userIds.add(q.getUserId());
            }
            // 添加最新回答ID
            answerIds.add(q.getLatestAnswerId());
        }

        // 创建用户和回答的映射表
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        Map<Long, InteractionReply> replyMap = new HashMap<>(answerIds.size());
        // 移除空的回答ID
        answerIds.remove(null);
        // 如果有回答ID，查询回答信息
        if (CollUtils.isNotEmpty(answerIds)) {
            List<InteractionReply> replies = replyService.listByIds(answerIds);
            for (InteractionReply reply : replies) {
                replyMap.put(reply.getId(), reply);
                // 如果回答不是匿名，添加用户ID
                if (!reply.getAnonymity()) {
                    userIds.add(reply.getUserId());
                }
            }

        }
        // 如果有用户ID，查询用户信息
        if (CollUtils.isNotEmpty(userIds)) {
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }
        // 构建返回结果列表
        List<QuestionVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion record : records) {
            // 复制问题属性到VO对象
            QuestionVO vo = BeanUtils.copyBean(record, QuestionVO.class);
            // 如果问题不是匿名，设置用户信息
            if (!record.getAnonymity()) {
                UserDTO userDTO = userMap.get(record.getUserId());
                if (userDTO != null) {
                    vo.setUserName(userDTO.getName());
                    vo.setUserIcon(userDTO.getIcon());
                }
            }
            // 获取最新回答信息
            InteractionReply reply = replyMap.get(record.getLatestAnswerId());
            if (reply != null) {
                // 设置回答内容
                vo.setLatestReplyContent(reply.getContent());
                // 如果回答不是匿名，设置回答者信息
                if (!reply.getAnonymity()) {
                    UserDTO user = userMap.get(reply.getUserId());
                    if (user != null) {
                        vo.setLatestReplyUser(user.getName());
                    }
                }
            }
            // 将VO对象添加到结果列表
            voList.add(vo);
        }
        // 返回分页结果
        return PageDTO.of(page, voList);
    }


    /**
     * 根据问题ID查询问题详情
     * @param id 问题ID
     * @return 返回问题详情对象，如果问题被隐藏则返回null
     */

    @Override
    public QuestionVO queryQuestionById(Long id) {
        // 根据ID获取问题实体
        InteractionQuestion question = getById(id);
        // 问题不存在（id 非法 / 已被删除）直接返回 null，与「被隐藏」同一语义。
        // 原来少了这一步，下一行 question.getHidden() 会 NPE → 500，前端只看到「服务异常」，
        // 分不清是「没这条数据」还是「后端挂了」
        if (question == null) {
            return null;
        }
        // 判断问题是否被隐藏，如果隐藏则返回null
        if (question.getHidden()) {
            return null;
        }
        UserDTO user = null;
        // 如果问题不是匿名发布，则获取发布用户信息
        if (Objects.equals(question.getAnonymity(), false)) {
            user = userClient.queryUserById(question.getUserId());
        }
        // 将问题实体转换为VO对象
        QuestionVO vo = BeanUtils.copyBean(question, QuestionVO.class);
        // 如果用户信息不为空，则设置用户名和头像
        if (user != null) {
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }
        return vo;
    }


    @Override
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        List<Long> coursesIds = null;
        if (StringUtils.isNotBlank(query.getCourseName())) {
            coursesIds = searchClient.queryCoursesIdByName(query.getCourseName());
            if (CollUtils.isEmpty(coursesIds)) {
                return PageDTO.empty(0L, 0L);
            }
        }
        Integer status = query.getStatus();
        LocalDateTime beginTime = query.getBeginTime();
        LocalDateTime endTime = query.getEndTime();
        Page<InteractionQuestion> page = lambdaQuery()
                .in(coursesIds != null, InteractionQuestion::getCourseId, coursesIds)
                .eq(status != null, InteractionQuestion::getStatus, status)
                .gt(beginTime != null, InteractionQuestion::getCreateTime, beginTime)
                .lt(endTime != null, InteractionQuestion::getCreateTime, endTime)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        Set<Long> userIds = new HashSet<>();
        Set<Long> cataIds = new HashSet<>(); // 章节ID， 节ID
        Set<Long> courseIds = new HashSet<>();
        for (InteractionQuestion question : records) {
            userIds.add(question.getUserId());
            courseIds.add(question.getCourseId());
            cataIds.add(question.getChapterId());
            cataIds.add(question.getSectionId());
        }

        List<UserDTO> users = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (CollUtils.isNotEmpty(users)) {
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }

        List<CourseSimpleInfoDTO> courseInfoList = courseClient.getSimpleInfoList(courseIds);
        Map<Long, CourseSimpleInfoDTO> courseMap = new HashMap<>(courseIds.size());
        if (CollUtils.isNotEmpty(courseInfoList)) {
            courseMap = courseInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        }
        List<CataSimpleInfoDTO> cataInfoList = catalogueClient.batchQueryCatalogue(cataIds);
        Map<Long, String> cataMap = new HashMap<>(cataIds.size());
        if (CollUtils.isNotEmpty(cataInfoList)) {
            cataMap = cataInfoList.stream().collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }

        List<QuestionAdminVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion q : records) {
            QuestionAdminVO vo = BeanUtils.copyBean(q, QuestionAdminVO.class);
            UserDTO user = userMap.get(q.getUserId());
            if (user != null) {
                vo.setUserName(user.getName());
            }
            CourseSimpleInfoDTO course = courseMap.get(q.getCourseId());
            if (course != null) {
                vo.setCourseName(course.getName());
                String categoryNames = categoryCache.getCategoryNames(course.getCategoryIds());
                vo.setCategoryName(categoryNames); // 三级分类名称
            }
            vo.setChapterName(cataMap.getOrDefault(q.getChapterId(), "")); // 章名称
            vo.setSectionName(cataMap.getOrDefault(q.getSectionId(), "")); // 节名称
            voList.add(vo);
        }
        return PageDTO.of(page, voList);
    }


    /**
     * 根据问题ID查询问题信息（管理员视角）
     *
     * @param id 问题ID
     * @return QuestionAdminVO 包含完整问题信息的管理员视图对象
     * @throws BadRequestException 当问题ID为空或问题不存在时抛出
     */
    @Override
    @Transactional
    public QuestionAdminVO adminQueryQuestionById(Long id) {
        // 参数校验：检查问题ID是否为空
        if (id == null) {
            throw new BadRequestException("问题ID不能为空！");
        }
        // 根据ID查询问题
        InteractionQuestion question = getById(id);
        // 检查问题是否存在
        if (question == null) {
            throw new BadRequestException("问题不存在！");
        }

        // 1. 基础属性复制：将问题实体对象复制到视图对象中
        QuestionAdminVO vo = BeanUtils.copyBean(question, QuestionAdminVO.class);

        // 2. 处理用户信息：查询并设置提问用户的信息
        if (question.getUserId() != null) {
            UserDTO userDTO = userClient.queryUserById(question.getUserId());
            if (userDTO != null) {
                vo.setUserName(userDTO.getName());
                vo.setUserIcon(userDTO.getIcon());
            }
        }

        // 3. 处理课程信息：查询并设置课程相关信息
        if (question.getCourseId() != null && question.getCourseId() > 0) {
            // 获取课程详细信息
            CourseFullInfoDTO courseInfo = courseClient.getCourseInfoById(question.getCourseId(), true, true);
            if (courseInfo != null) {
                vo.setCourseName(courseInfo.getName());
                // 获取老师名字并拼接
                if (CollUtils.isNotEmpty(courseInfo.getTeacherIds())) {
                    List<UserDTO> teachers = userClient.queryUserByIds(courseInfo.getTeacherIds());
                    if (CollUtils.isNotEmpty(teachers)) {
                        // 将所有老师名字用逗号拼接
                        String teacherName = teachers.stream()
                                .map(UserDTO::getName)
                                .filter(Objects::nonNull)
                                .collect(Collectors.joining(","));
                        vo.setTeacherName(teacherName);
                    }
                }
                // 处理课程分类信息
                List<Long> categoryIds = courseInfo.getCategoryIds();
                if (CollUtils.isNotEmpty(categoryIds)) {
                    String categoryNames = categoryCache.getCategoryNames(categoryIds);
                    vo.setCategoryName(categoryNames);
                }
                // 根据问题的 chapterId 找到对应的章名称
                if (question.getChapterId() != null && CollUtils.isNotEmpty(courseInfo.getChapters())) {
                    for (CatalogueDTO chapter : courseInfo.getChapters()) {
                        if (chapter.getId().equals(question.getChapterId())) {
                            vo.setChapterName(chapter.getName());
                            break;
                        }
                    }

                }
            }
        }

        // 处理小节信息：根据sectionId查询小节名称
        if (question.getSectionId() != null) {
            List<CataSimpleInfoDTO> secList = catalogueClient.batchQueryCatalogue(CollUtils.singletonList(question.getSectionId()));
            if (CollUtils.isNotEmpty(secList)) {
                vo.setSectionName(secList.get(0).getName());
            }
        }
        // 更新问题状态为已审核
        lambdaUpdate()
                .set(InteractionQuestion::getStatus, QuestionStatus.CHECKED)
                .eq(InteractionQuestion::getId, id)
                .update();
        return vo;

    }
}
