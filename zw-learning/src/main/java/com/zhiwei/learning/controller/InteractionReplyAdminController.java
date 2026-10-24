package com.zhiwei.learning.controller;


import com.zhiwei.common.domain.dto.PageDTO;
import com.zhiwei.learning.domian.dto.ReplyDTO;
import com.zhiwei.learning.domian.query.ReplyPageQuery;
import com.zhiwei.learning.domian.vo.ReplyVO;
import com.zhiwei.learning.service.IInteractionReplyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 互动问题的回答或评论 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-05-10
 */
@RestController
@RequestMapping("/admin/replies")
@RequiredArgsConstructor
@Api(tags = "管理端互动回答或评论接口")
public class InteractionReplyAdminController {
    private final IInteractionReplyService replyService;


    @ApiOperation("管理端分页查询互动回答或评论")
    @GetMapping("/page")
    public PageDTO<ReplyVO> listReplies(ReplyPageQuery query) {
        return replyService.listRepliesAdmin(query);
    }

    @ApiOperation("管理端修改互动回答或评论的隐藏状态")
    @PutMapping("/{id}/hidden/{hidden}")
    public void updateReplyHidden(@PathVariable Long id, @PathVariable Boolean hidden) {
        replyService.updateReplyHidden(id, hidden);
    }

}
