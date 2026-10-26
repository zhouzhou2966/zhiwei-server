package com.zhiwei.api.client.course.fallback;

import com.zhiwei.api.client.course.CatalogueClient;
import com.zhiwei.api.dto.course.CataSimpleInfoDTO;
import com.zhiwei.common.utils.CollUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * CatalogueClient 的降级工厂。
 *
 * <p>为什么需要它：CatalogueClient 和 CourseClient 打的是同一个 course-service。
 * 只给 CourseClient 配降级是不够的 —— learning 里 5 处调用 catalogueClient，
 * 其中 {@code LearningLessonServiceImpl.getNowLesson} 在取到课程信息后还要用
 * {@code batchQueryCatalogue(latestSectionId)} 查「学到第几节」。
 * course 一停，这里照样抛 RetryableException，被 CommonExceptionAdvice 兜成
 * {@code {"code":-1,"msg":""}}，前端只看到「没数据、也没提示」，等于降级白做。
 *
 * <p>降级语义：返回空列表。5 个调用点对空列表都是安全的 ——
 * 3 处包了 {@code CollUtils.isNotEmpty} 才往下走，2 处在异步线程里直接
 * {@code stream().collect(...)}，空集合 collect 出空 Map。
 * 表现是「章/节名称、学到第几节」这些展示字段变空，学习进度照常返回。
 *
 * <p>⚠️ 必须打 error 日志，否则降级会把故障伪装成「正常但没数据」，course 挂久了没人发现。
 */
@Slf4j
public class CatalogueClientFallback implements FallbackFactory<CatalogueClient> {

    @Override
    public CatalogueClient create(Throwable cause) {
        log.error("查询course-service目录信息异常，已降级返回空数据", cause);
        return new CatalogueClient() {

            @Override
            public List<CataSimpleInfoDTO> batchQueryCatalogue(Iterable<Long> ids) {
                return CollUtils.emptyList();
            }
        };
    }
}
