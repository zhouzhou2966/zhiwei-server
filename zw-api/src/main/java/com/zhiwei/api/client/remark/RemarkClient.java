package com.zhiwei.api.client.remark;

import com.zhiwei.api.client.remark.fallback.RemarkClientFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

@FeignClient(value = "remark-service", fallbackFactory = RemarkClientFallback.class)

public interface RemarkClient {
    @GetMapping("/likes/list")
    Set<Long> isBizLike(@RequestParam("bizIds") Iterable<Long> bizIds,
                        @RequestParam("bizType") String bizType);
}
