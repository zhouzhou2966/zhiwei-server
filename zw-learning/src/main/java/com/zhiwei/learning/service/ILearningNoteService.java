package com.zhiwei.learning.service;

import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.learning.domian.dto.NoteFormDTO;
import com.zhiwei.learning.domian.dto.NoteUpdateDTO;
import com.zhiwei.learning.domian.po.LearningNote;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiwei.learning.domian.query.NoteAdminPageQuery;
import com.zhiwei.learning.domian.query.NotePageQuery;
import com.zhiwei.learning.domian.vo.NoteAdminDetailVO;
import com.zhiwei.learning.domian.vo.NoteAdminPageVO;
import com.zhiwei.learning.domian.vo.NotePageVO;

import javax.validation.Valid;


/**
 * <p>
 * 学习笔记表 服务类
 * </p>
 *
 * @author author
 * @since 2026-07-08
 */
public interface ILearningNoteService extends IService<LearningNote> {
    // 保存笔记
    void saveNote(NoteFormDTO noteFormDTO);

    // 采集笔记
    void gatherNotes(Long id);

    // 取消采集
    void cancelGatherNotes(Long id);

    // 更新笔记
    void updateNote(Long id, @Valid NoteUpdateDTO noteUpdateDTO);

    // 删除笔记
    void deleteNoteById(Long id);

    // 用户端分页查询笔记
    PageDTO<NotePageVO> pageQuery(NotePageQuery notePageQuery);

    // 管理员端分页查询笔记
    PageDTO<NoteAdminPageVO> pageAdminPageQuery(NoteAdminPageQuery notePageQuery);

    // 管理员端查询笔记详情
    NoteAdminDetailVO getNoteAdminDetail(Long id);
         // 更新笔记隐藏状态
    void updateNoteHiddenStatus(Long id, Boolean hidden);
}