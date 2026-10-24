package com.zhiwei.learning.controller;


import com.zhiwei.common.domain.dto.PageDTO;

import com.zhiwei.learning.domian.query.QuestionAdminPageQuery;

import com.zhiwei.learning.domian.vo.QuestionAdminVO;

import com.zhiwei.learning.service.IInteractionQuestionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 互动提问的问题表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-10
 */
@RestController
@RequestMapping("/admin/questions")
@RequiredArgsConstructor
@Api(tags = "互动问题")
public class InteractionQuestionAdminController {
    private final IInteractionQuestionService interactionQuestionService;

    @ApiOperation("管理端分页查询互动问题")
    @GetMapping("/page")
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        return interactionQuestionService.queryQuestionPageAdmin(query);
    }

    @ApiOperation("管理端修改问题隐藏状态")
    @PutMapping("/{id}/hidden/{hidden}")
    public void updateQuestionHidden(@PathVariable Long id, @PathVariable Boolean hidden) {
        interactionQuestionService.updateQuestionHidden(id, hidden);
    }

    @ApiOperation("管理端查询问题详情")
    @GetMapping("/{id}")
    public QuestionAdminVO queryQuestionById(@PathVariable Long id) {
        return interactionQuestionService.adminQueryQuestionById(id);
    }
}
