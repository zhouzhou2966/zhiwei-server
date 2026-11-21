package com.zhiwei.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiwei.api.cache.CategoryCache;
import com.zhiwei.api.client.course.CatalogueClient;
import com.zhiwei.api.client.course.CourseClient;
import com.zhiwei.api.client.search.SearchClient;
import com.zhiwei.api.client.user.UserClient;
import com.zhiwei.api.dto.course.CataSimpleInfoDTO;
import com.zhiwei.api.dto.course.CourseSimpleInfoDTO;
import com.zhiwei.api.dto.user.UserDTO;
import com.zhiwei.common.autoconfigure.mq.RabbitMqHelper;
import com.zhiwei.common.constants.MqConstants;
import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.BizIllegalException;
import com.zhiwei.common.utils.BeanUtils;
import com.zhiwei.common.utils.CollUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.common.utils.UserContext;
import com.zhiwei.learning.domian.dto.NoteFormDTO;
import com.zhiwei.learning.domian.dto.NoteUpdateDTO;
import com.zhiwei.learning.domian.po.LearningNote;
import com.zhiwei.learning.domian.query.NoteAdminPageQuery;
import com.zhiwei.learning.domian.query.NotePageQuery;
import com.zhiwei.learning.domian.vo.NoteAdminDetailVO;
import com.zhiwei.learning.domian.vo.NoteAdminPageVO;
import com.zhiwei.learning.domian.vo.NotePageVO;
import com.zhiwei.learning.mapper.LearningNoteMapper;
import com.zhiwei.learning.service.ILearningNoteService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.zhiwei.common.constants.MqConstants.Exchange.LEARNING_EXCHANGE;

/**
 * <p>
 * 学习笔记表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-07-08
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningNoteServiceImpl extends ServiceImpl<LearningNoteMapper, LearningNote> implements ILearningNoteService {

    private final UserClient userClient;
    private final CatalogueClient catalogueClient;
    private final CourseClient courseClient;
    private final SearchClient searchClient;
    private final CategoryCache categoryCache;
    private final RabbitMqHelper rabbitMqHelper;

    @Resource(name = "asyncServiceExecutor")
    private ThreadPoolTaskExecutor asyncServiceExecutor;

    @Override
    public void saveNote(NoteFormDTO noteFormDTO) {
        Long userId = UserContext.getUserId();
        LearningNote learningNote = new LearningNote();
        learningNote.setUserId(userId);
        BeanUtils.copyProperties(noteFormDTO, learningNote);
        boolean saved = save(learningNote);
        if (saved) {
            // 发送消息到队列
            try {
                rabbitMqHelper.send(LEARNING_EXCHANGE, MqConstants.Key.WRITE_NOTE, userId);
            } catch (Exception e) {
                log.error("发送发布笔记积分MQ消息失败", e);
            }
        }
    }


    @Override
    public void gatherNotes(Long id) {
        if (id == null) return;
        Long userId = UserContext.getUserId();
        LearningNote learningNote = getById(id);
        if (learningNote == null || userId.equals(learningNote.getUserId()) || learningNote.getIsPrivate()) return;
        // 已经采集过了 → 不重复采集
        long count = lambdaQuery()
                .eq(LearningNote::getGatheredNoteId, id)
                .eq(LearningNote::getUserId, userId)
                .count();
        if (count > 0) return;
        LearningNote note = new LearningNote();
        note.setUserId(userId);
        note.setCourseId(learningNote.getCourseId());
        note.setChapterId(learningNote.getChapterId());
        note.setSectionId(learningNote.getSectionId());
        note.setContent(learningNote.getContent());
        note.setIsPrivate(true);
        note.setNoteMoment(learningNote.getNoteMoment());
        note.setGatheredNoteId(id);
        save(note);
    }

    @Override
    public void cancelGatherNotes(Long id) {
        if (id == null) return;
        Long userId = UserContext.getUserId();
        lambdaUpdate()
                .eq(LearningNote::getGatheredNoteId, id)
                .eq(LearningNote::getUserId, userId)
                .remove();
    }


    @Override
    public void updateNote(Long id, NoteUpdateDTO noteUpdateDTO) {
        if (id == null) return;
        Long userId = UserContext.getUserId();
        lambdaUpdate()
                .eq(LearningNote::getId, id)
                .eq(LearningNote::getUserId, userId)
                .set(LearningNote::getContent, noteUpdateDTO.getContent())
                .set(LearningNote::getIsPrivate, noteUpdateDTO.getIsPrivate())
                .update();
    }


    @Override
    public void deleteNoteById(Long id) {
        if (id == null) throw new BizIllegalException("笔记id不能为空");
        Long userId = UserContext.getUserId();
        // 1. 先判断是否本人笔记
        if (!lambdaQuery()
                .eq(LearningNote::getId, id)
                .eq(LearningNote::getUserId, userId)
                .exists()) throw new BadRequestException("不是本人笔记");

        // 2. 级联删除采集记录
        lambdaUpdate()
                .eq(LearningNote::getGatheredNoteId, id)
                .remove();
        // 3. 删除原笔记
        lambdaUpdate()
                .eq(LearningNote::getId, id)
                .remove();

    }


    @Override
    public PageDTO<NotePageVO> pageQuery(NotePageQuery notePageQuery) {
        Long courseId = notePageQuery.getCourseId();
        Long sectionId = notePageQuery.getSectionId();
        Boolean onlyMine = notePageQuery.getOnlyMine();
        if (onlyMine == null) {
            throw new BizIllegalException("是否只查询我的笔记不能为空");
        }
        Page<LearningNote> page = lambdaQuery()
                .eq(courseId != null, LearningNote::getCourseId, courseId)
                .eq(sectionId != null, LearningNote::getSectionId, sectionId)
                .eq(onlyMine, LearningNote::getUserId, UserContext.getUserId())
                .eq(!onlyMine, LearningNote::getIsPrivate, false)
                .eq(!onlyMine, LearningNote::getHidden, false)
                .page(notePageQuery.toMpPageDefaultSortByCreateTimeDesc());
        List<LearningNote> records = page.getRecords();
        if (CollUtils.isEmpty(records)) return PageDTO.empty(page);
        Set<Long> userIds = records.stream().map(LearningNote::getUserId).collect(Collectors.toSet());
        List<UserDTO> userInfo = CollUtils.isEmpty(userIds) ? Collections.emptyList() : userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userMap = userInfo.stream().collect(Collectors.toMap(UserDTO::getId, u -> u, (v1, v2) -> v1));
        // 课程名：courseId/sectionId 由 copyProperties 直接带过去，课程名要跨服务批量补一次
        // （与 pageAdminPageQuery 的取法一致，都是 CourseClient.getSimpleInfoList）
        Set<Long> courseIds = records.stream()
                .map(LearningNote::getCourseId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        List<CourseSimpleInfoDTO> courseList = CollUtils.isEmpty(courseIds)
                ? Collections.emptyList()
                : courseClient.getSimpleInfoList(courseIds);
        Map<Long, CourseSimpleInfoDTO> courseMap = courseList.stream()
                .collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c, (v1, v2) -> v1));
        List<NotePageVO> voList = records.stream()
                .map(
                        ln -> {
                            NotePageVO vo = new NotePageVO();
                            BeanUtils.copyProperties(ln, vo);
                            vo.setIsGathered(ln.getGatheredNoteId() != null);
                            UserDTO user = userMap.get(ln.getUserId());
                            if (user != null) {
                                vo.setAuthorId(user.getId());
                                vo.setAuthorName(user.getName());
                                vo.setAuthorIcon(user.getIcon());
                            }
                            CourseSimpleInfoDTO course = courseMap.get(ln.getCourseId());
                            if (course != null) {
                                vo.setCourseName(course.getName());
                            }
                            return vo;
                        })
                .collect(Collectors.toList());

        return PageDTO.of(page, voList);

    }


    @Override
    public PageDTO<NoteAdminPageVO> pageAdminPageQuery(NoteAdminPageQuery notePageQuery) {
        // 1. 处理课程名称关键字搜索 → 获取匹配的课程ID列表
        String name = notePageQuery.getName();
        List<Long> courseIdsByName = null;
        if (StringUtils.isNotBlank(name)) {
            courseIdsByName = searchClient.queryCoursesIdByName(name);
            if (CollUtils.isEmpty(courseIdsByName)) {
                return PageDTO.empty(0L, 0L);
            }
        }

        // 2. 分页查询笔记
        Boolean hidden = notePageQuery.getHidden();
        LocalDateTime beginTime = notePageQuery.getBeginTime();
        LocalDateTime endTime = notePageQuery.getEndTime();
        Page<LearningNote> page = lambdaQuery()
                .in(courseIdsByName != null, LearningNote::getCourseId, courseIdsByName)
                .eq(hidden != null, LearningNote::getHidden, hidden)
                .ge(beginTime != null, LearningNote::getUpdateTime, beginTime)
                .le(endTime != null, LearningNote::getUpdateTime, endTime)
                .page(notePageQuery.toMpPageDefaultSortByUpdateTimeDesc());
        List<LearningNote> records = page.getRecords();
        if (CollUtils.isEmpty(records)) return PageDTO.empty(page);

        // 3. 统计被采集次数
        Set<Long> noteIds = records.stream().map(LearningNote::getId).collect(Collectors.toSet());
        Map<Long, Long> usedTimesMap = lambdaQuery()
                .select(LearningNote::getGatheredNoteId)
                .in(LearningNote::getGatheredNoteId, noteIds)
                .list()
                .stream()
                .collect(Collectors.groupingBy(LearningNote::getGatheredNoteId, Collectors.counting()));

        // 4. 收集关联ID

        Set<Long> userIds = new HashSet<>();
        Set<Long> courseIds = new HashSet<>();
        Set<Long> catIds = new HashSet<>();
        for (LearningNote note : records) {
            userIds.add(note.getUserId());
            courseIds.add(note.getCourseId());
            catIds.add(note.getChapterId());
            catIds.add(note.getSectionId());
        }

        // 5. 远程查询——并行执行（减少网络耗时）
        Long currentUserId = UserContext.getUserId();
        CompletableFuture<List<UserDTO>> userFuture =
                CollUtils.isEmpty(userIds) ?
                        CompletableFuture.completedFuture(Collections.emptyList()) :
                        CompletableFuture.supplyAsync(() -> {
                            UserContext.setUserId(currentUserId);
                            return userClient.queryUserByIds(userIds);
                        }, asyncServiceExecutor);

        CompletableFuture<List<CourseSimpleInfoDTO>> courseFuture =
                CollUtils.isEmpty(courseIds) ?
                        CompletableFuture.completedFuture(Collections.emptyList()) :
                        CompletableFuture.supplyAsync(() -> {
                            UserContext.setUserId(currentUserId);
                            return courseClient.getSimpleInfoList(courseIds);
                        }, asyncServiceExecutor);

        CompletableFuture<List<CataSimpleInfoDTO>> catFuture =
                CollUtils.isEmpty(catIds) ?
                        CompletableFuture.completedFuture(Collections.emptyList()) :
                        CompletableFuture.supplyAsync(() -> {
                            UserContext.setUserId(currentUserId);
                            return catalogueClient.batchQueryCatalogue(catIds);
                        }, asyncServiceExecutor);

        // 等待所有异步任务完成
        CompletableFuture.allOf(userFuture, courseFuture, catFuture).join();

        // 获取结果并构建Map（加 merge 函数避免重复 key 抛异常）
        List<UserDTO> userInfo = userFuture.join();
        Map<Long, UserDTO> userMap = userInfo.stream()
                .collect(Collectors.toMap(UserDTO::getId, u -> u, (v1, v2) -> v1));

        List<CourseSimpleInfoDTO> courseList = courseFuture.join();
        Map<Long, CourseSimpleInfoDTO> courseMap = courseList.stream()
                .collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c, (v1, v2) -> v1));

        List<CataSimpleInfoDTO> catList = catFuture.join();
        Map<Long, String> catNameMap = catList.stream()
                .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName, (v1, v2) -> v1));

        // 6. 组装VO
        List<NoteAdminPageVO> voList = records.stream().map(ln -> {
            NoteAdminPageVO vo = BeanUtils.copyBean(ln, NoteAdminPageVO.class);
            // 作者名称
            UserDTO user = userMap.get(ln.getUserId());
            if (user != null) {
                vo.setAuthorName(user.getName());
            }
            // 被采集次数
            vo.setUsedTimes(usedTimesMap.getOrDefault(ln.getId(), 0L).intValue());
            // 课程名称
            CourseSimpleInfoDTO course = courseMap.get(ln.getCourseId());
            if (course != null) {
                vo.setCourseName(course.getName());
            }
            // 章名称
            vo.setChapterName(catNameMap.getOrDefault(ln.getChapterId(), ""));
            // 节名称
            vo.setSectionName(catNameMap.getOrDefault(ln.getSectionId(), ""));
            return vo;
        }).collect(Collectors.toList());

        return PageDTO.of(page, voList);
    }


    @Override
    public NoteAdminDetailVO getNoteAdminDetail(Long id) {
        if (id == null) throw new BizIllegalException("笔记ID不能为空");
        LearningNote note = getById(id);
        if (note == null) throw new BizIllegalException("笔记不存在");

        NoteAdminDetailVO vo = BeanUtils.copyBean(note, NoteAdminDetailVO.class);

        // 捕获当前线程的用户信息，供异步线程使用（否则 Feign 拦截器取不到 userId 导致 401）
        Long currentUserId = UserContext.getUserId();

        // 1. 并行查询课程信息、章节目录、作者信息
        CompletableFuture<List<CourseSimpleInfoDTO>> courseFuture = CompletableFuture
                .supplyAsync(() -> {
                    UserContext.setUserId(currentUserId);
                    return courseClient.getSimpleInfoList(Collections.singletonList(note.getCourseId()));
                }, asyncServiceExecutor);

        List<Long> catIds = new ArrayList<>(2);
        if (note.getChapterId() != null) catIds.add(note.getChapterId());
        if (note.getSectionId() != null) catIds.add(note.getSectionId());
        CompletableFuture<List<CataSimpleInfoDTO>> catFuture = CompletableFuture
                .supplyAsync(() -> {
                    UserContext.setUserId(currentUserId);
                    return catalogueClient.batchQueryCatalogue(catIds);
                }, asyncServiceExecutor);

        CompletableFuture<UserDTO> userFuture = note.getUserId() == null ? null
                : CompletableFuture.supplyAsync(() -> {
            UserContext.setUserId(currentUserId);
            return userClient.queryUserById(note.getUserId());
        }, asyncServiceExecutor);

        // 2. 并行查询采集信息
        CompletableFuture<List<LearningNote>> gatheredFuture = CompletableFuture
                .supplyAsync(() -> {
                    UserContext.setUserId(currentUserId);
                    return lambdaQuery()
                            .eq(LearningNote::getGatheredNoteId, note.getId())
                            .list();
                }, asyncServiceExecutor);

        // 等待所有异步任务完成
        CompletableFuture.allOf(
                courseFuture, catFuture, gatheredFuture,
                userFuture != null ? userFuture : CompletableFuture.completedFuture(null)
        ).join();

        // 3. 组装课程信息 + 分类名称
        CourseSimpleInfoDTO course = CollUtils.isEmpty(courseFuture.join()) ? null : courseFuture.join().get(0);
        if (course != null) {
            vo.setCourseName(course.getName());
            if (CollUtils.isNotEmpty(course.getCategoryIds())) {
                vo.setCategoryNames(categoryCache.getCategoryNames(course.getCategoryIds()));
            }
        }

        // 4. 组装章/节名称
        List<CataSimpleInfoDTO> catList = catFuture.join();
        if (CollUtils.isNotEmpty(catList)) {
            Map<Long, String> catNameMap = catList.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName, (v1, v2) -> v1));
            vo.setChapterName(catNameMap.getOrDefault(note.getChapterId(), ""));
            vo.setSectionName(catNameMap.getOrDefault(note.getSectionId(), ""));
        }

        // 5. 组装作者信息
        if (userFuture != null) {
            UserDTO user = userFuture.join();
            if (user != null) {
                vo.setAuthorName(user.getName());
                vo.setAuthorPhone(user.getCellPhone());
            }
        }

        // 6. 组装采集信息
        List<LearningNote> gatheredNotes = gatheredFuture.join();
        if (CollUtils.isNotEmpty(gatheredNotes)) {
            vo.setUsedTimes(gatheredNotes.size());
            Set<Long> gatherUserIds = gatheredNotes.stream()
                    .map(LearningNote::getUserId)
                    .collect(Collectors.toSet());
            if (CollUtils.isNotEmpty(gatherUserIds)) {
                List<UserDTO> gatherUsers = userClient.queryUserByIds(gatherUserIds);
                vo.setGathers(gatherUsers.stream().map(UserDTO::getName).collect(Collectors.toList()));
            }
        }
        return vo;
    }


    @Override
    public void updateNoteHiddenStatus(Long id, Boolean hidden) {
        if (id == null) throw new BizIllegalException("笔记ID不能为空");
        if (hidden == null) throw new BizIllegalException("隐藏状态不能为空");

        boolean updated = lambdaUpdate()
                .set(LearningNote::getHidden, hidden)
                .eq(LearningNote::getId, id)
                .update();
        if (!updated) throw new BizIllegalException("笔记不存在");
    }
}