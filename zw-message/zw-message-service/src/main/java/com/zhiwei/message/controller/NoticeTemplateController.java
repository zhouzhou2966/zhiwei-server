package com.zhiwei.message.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.message.domain.dto.NoticeTemplateDTO;
import com.zhiwei.message.domain.dto.NoticeTemplateFormDTO;
import com.zhiwei.message.domain.query.NoticeTemplatePageQuery;
import com.zhiwei.message.service.INoticeTemplateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 通知模板 前端控制器
 * </p>
 *
 * @author zhiwei
 * @since 2022-08-19
 */
@RestController
@RequestMapping("/notice-templates")
@Tag(name = "通知模板管理接口")
@RequiredArgsConstructor
public class NoticeTemplateController {

    private final INoticeTemplateService noticeTemplateService;

    @PostMapping
    @Operation(summary = "新增通知模板")
    public Long saveNoticeTemplate(@RequestBody NoticeTemplateFormDTO noticeTemplateFormDTO){
        return noticeTemplateService.saveNoticeTemplate(noticeTemplateFormDTO);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新通知模板")
    public void updateNoticeTemplate(
            @RequestBody NoticeTemplateFormDTO noticeTemplateFormDTO,
            @Parameter(description = "模板id", example = "1") @PathVariable("id") Long id){
        noticeTemplateFormDTO.setId(id);
        noticeTemplateService.updateNoticeTemplate(noticeTemplateFormDTO);
    }

    @GetMapping
    @Operation(summary = "分页查询通知模板")
    public PageDTO<NoticeTemplateDTO> queryNoticeTemplates(NoticeTemplatePageQuery pageQuery){
        return noticeTemplateService.queryNoticeTemplates(pageQuery);
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据id查询模板")
    public NoticeTemplateDTO queryNoticeTemplate(@Parameter(description = "模板id", example = "1") @PathVariable("id") Long id){
        return noticeTemplateService.queryNoticeTemplate(id);
    }
}
