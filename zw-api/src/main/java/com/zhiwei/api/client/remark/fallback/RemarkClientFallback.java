package com.zhiwei.api.client.remark.fallback;

import com.zhiwei.api.client.learning.LearningClient;
import com.zhiwei.api.client.remark.RemarkClient;
import com.zhiwei.api.dto.leanring.LearningLessonDTO;
import com.zhiwei.common.utils.CollUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Set;

@Slf4j
public class RemarkClientFallback implements FallbackFactory<RemarkClient> {

    @Override
    public RemarkClient create(Throwable cause) {
        log.error("查询remark-service服务异常", cause);
        return new RemarkClient() {
            @Override
            public Set<Long> isBizLike(Iterable<Long> bizIds, String bizType) {
                return CollUtils.emptySet();
            }

        };
    }
}
