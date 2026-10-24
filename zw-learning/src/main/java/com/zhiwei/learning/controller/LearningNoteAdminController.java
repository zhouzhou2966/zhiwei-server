package com.zhiwei.learning.controller;


import com.zhiwei.common.domain.dto.PageDTO;

import com.zhiwei.learning.domian.query.NoteAdminPageQuery;

import com.zhiwei.learning.domian.vo.NoteAdminDetailVO;
import com.zhiwei.learning.domian.vo.NoteAdminPageVO;

import com.zhiwei.learning.service.ILearningNoteService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;



/**
 * <p>
 * 学习笔记表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-07-08
 */
@RestController
@RequestMapping("/admin/notes")
@RequiredArgsConstructor
@Api(tags = "管理员端学习笔记管理")
public class LearningNoteAdminController {

    private final ILearningNoteService learningNoteService;


    @ApiOperation("管理员端分页查询笔记")
    @GetMapping("/page")
    public PageDTO<NoteAdminPageVO> pageAdminPageQuery(NoteAdminPageQuery notePageQuery) {
        return learningNoteService.pageAdminPageQuery(notePageQuery);
    }

    @ApiOperation("管理员端查询笔记详情")
    @GetMapping("/{id}")
    public NoteAdminDetailVO getNoteAdminDetail(@PathVariable Long id) {
        return learningNoteService.getNoteAdminDetail(id);
    }

    @ApiOperation("管理员端更新笔记隐藏状态")
    @PutMapping("/{id}/hidden/{hidden}")
    public void updateNoteHiddenStatus(@PathVariable Long id, @PathVariable Boolean hidden) {
        learningNoteService.updateNoteHiddenStatus(id, hidden);
    }
}