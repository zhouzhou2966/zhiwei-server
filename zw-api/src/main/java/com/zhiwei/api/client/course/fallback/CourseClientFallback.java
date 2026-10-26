package com.zhiwei.api.client.course.fallback;

import com.zhiwei.api.client.course.CourseClient;
import com.zhiwei.api.dto.course.CourseFullInfoDTO;
import com.zhiwei.api.dto.course.CourseSearchDTO;
import com.zhiwei.api.dto.course.CourseSimpleInfoDTO;
import com.zhiwei.api.dto.course.MediaQuoteDTO;
import com.zhiwei.api.dto.course.SectionInfoDTO;
import com.zhiwei.api.dto.course.SubNumAndCourseNumDTO;
import com.zhiwei.common.utils.CollUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * CourseClient 的降级工厂。
 *
 * <p>为什么需要它：course-service 是「课程主数据」的唯一来源，learning 的 learning_lesson
 * 表只存 course_id 和学习进度，课程名称、封面、章节数全部要实时向 course 取。
 * 没有降级时，course 一旦不可用（实例没注册、被 docker stop、网络断），Feign 抛
 * RetryableException，被 CommonExceptionAdvice 兜成 500，「学习中心」整页拿不到数据。
 *
 * <p>降级后的语义是「拿不到课程展示信息，但学习进度照常返回」：调用方本就有
 * {@code if (cInfo != null)} 判空（LearningLessonServiceImpl 的 getNowLesson / queryMyLessons /
 * queryMyPlans 三处），所以会退化成「课程卡片没有名字」，而不是整页报错。
 *
 * <p>⚠️ 这里必须打 error 日志：降级会把故障伪装成「正常但没数据」，不打日志的话
 * course 挂了很久都没人发现。cause 的 message 里带具体 URL，能定位到是哪个接口。
 */
@Slf4j
public class CourseClientFallback implements FallbackFactory<CourseClient> {

    @Override
    public CourseClient create(Throwable cause) {
        log.error("查询course-service服务异常，已降级返回空数据", cause);
        return new CourseClient() {

            @Override
            public List<SubNumAndCourseNumDTO> infoByTeacherIds(Iterable<Long> teacherIds) {
                return CollUtils.emptyList();
            }

            @Override
            public SectionInfoDTO sectionInfo(Long sectionId) {
                return null;
            }

            @Override
            public List<MediaQuoteDTO> mediaUserInfo(Iterable<Long> mediaIds) {
                return CollUtils.emptyList();
            }

            @Override
            public CourseSearchDTO getSearchInfo(Long id) {
                return null;
            }

            @Override
            public List<CourseSimpleInfoDTO> getSimpleInfoList(Iterable<Long> ids) {
                return CollUtils.emptyList();
            }

            @Override
            public CourseFullInfoDTO getCourseInfoById(Long id, boolean withCatalogue, boolean withTeachers) {
                return null;
            }
        };
    }
}
