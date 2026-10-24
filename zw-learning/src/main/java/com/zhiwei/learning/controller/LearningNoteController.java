package com.zhiwei.learning.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.learning.domian.dto.NoteFormDTO;
import com.zhiwei.learning.domian.dto.NoteUpdateDTO;
import com.zhiwei.learning.domian.query.NotePageQuery;
import com.zhiwei.learning.domian.vo.NotePageVO;
import com.zhiwei.learning.service.ILearningNoteService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * <p>
 * 学习笔记表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-07-08
 */
@RestController
@RequestMapping("/notes")
@RequiredArgsConstructor
@Tag(name = "学习笔记表")
public class LearningNoteController {

    private final ILearningNoteService learningNoteService;

    @Operation(summary = "保存笔记")
    @PostMapping
    public void saveNote(@Valid @RequestBody NoteFormDTO noteFormDTO) {
        learningNoteService.saveNote(noteFormDTO);
    }

    @Operation(summary = "采集笔记")
    @PostMapping("/gathers/{id}")
    public void gatherNotes(@PathVariable("id") Long id) {
        learningNoteService.gatherNotes(id);
    }

    @Operation(summary = "取消采集")
    @DeleteMapping("/gathers/{id}")
    public void cancelGatherNotes(@PathVariable("id") Long id) {
        learningNoteService.cancelGatherNotes(id);
    }

    @Operation(summary = "更新笔记")
    @PutMapping("/{id}")
    public void updateNote(@PathVariable("id") Long id, @Valid @RequestBody NoteUpdateDTO noteUpdateDTO) {
        learningNoteService.updateNote(id, noteUpdateDTO);
    }

    @Operation(summary = "删除笔记")
    @DeleteMapping("/{id}")
    public void deleteNote(@PathVariable("id") Long id) {
        learningNoteService.deleteNoteById(id);
    }

    @Operation(summary = "用户端分页查询笔记")
    @GetMapping("/page")
    public PageDTO<NotePageVO> pageQuery(NotePageQuery notePageQuery) {
        return learningNoteService.pageQuery(notePageQuery);
    }
}