package com.zhiwei.api.client.learning.fallback;

import com.zhiwei.api.client.learning.LearningClient;
import com.zhiwei.api.dto.leanring.LearningLessonDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

@Slf4j
public class LearningClientFallback implements FallbackFactory<LearningClient> {

    @Override
    public LearningClient create(Throwable cause) {
        log.error("查询学习服务异常", cause);
        return new LearningClient() {
            @Override
            public Long countLearningLessonByCourse(Long courseId) {
                return 0L;
            }

            @Override
            public Long isLessonValid(Long courseId) {
                return null;
            }

            @Override
            public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
                return null;
            }
        };
    }
}
