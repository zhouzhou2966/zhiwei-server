package com.zhiwei.api.client.course;

import com.zhiwei.api.client.course.fallback.CatalogueClientFallback;
import com.zhiwei.api.dto.course.CataSimpleInfoDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

// 与 CourseClient 打同一个 course-service，必须一起降级：
// getNowLesson 取完课程信息还要查「学到第几节」，只降 CourseClient 这里照样抛异常。
@FeignClient(contextId = "catalogue", value = "course-service", path = "catalogues",
        fallbackFactory = CatalogueClientFallback.class)
public interface CatalogueClient {

    /**
     * 根据目录id列表查询目录信息
     *
     * @param ids 目录id列表
     * @return id列表中对应的目录基础信息
     */
    @GetMapping("/batchQuery")
    List<CataSimpleInfoDTO> batchQueryCatalogue(@RequestParam("ids") Iterable<Long> ids);


}